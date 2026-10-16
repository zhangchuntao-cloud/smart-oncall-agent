package com.smartoncall.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.smartoncall.agent.guard.AgentBudget;
import com.smartoncall.agent.guard.AgentBudgetExceededException;
import com.smartoncall.agent.guard.AgentGuardProperties;
import com.smartoncall.agent.guard.LoopGuardToolInterceptor;
import com.smartoncall.agent.guard.MeteredChatModel;
import com.smartoncall.agent.guard.ToolResultTruncatingInterceptor;
import com.smartoncall.agent.tool.DateTimeTools;
import com.smartoncall.agent.tool.InternalDocsTools;
import com.smartoncall.agent.tool.QueryLogsTools;
import com.smartoncall.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 *
 * <p><b>关于护栏</b>：这个类原来把资源约束全写进了提示词（"连续 3 次失败就停止"），
 * 靠模型自觉遵守——那等于没有约束。现在改成三层代码强制：
 * <ol>
 *   <li><b>迭代上限</b>：{@link CompileConfig#recursionLimit(int)} 限制 Supervisor 图的递归步数</li>
 *   <li><b>资源预算</b>：{@link MeteredChatModel} 包住 ChatModel，逐次累计 token 与调用次数，
 *       超限抛 {@link AgentBudgetExceededException} 终止运行</li>
 *   <li><b>墙钟超时</b>：外层 Future 超时后取消，并且 {@link AgentBudget} 会在下一次
 *       模型调用前检查耗时——因为阻塞中的 HTTP 调用无法被强行中断，
 *       真正生效的是这个"下一次调用前"的协作式检查</li>
 * </ol>
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    @Autowired
    private AgentGuardProperties guardProperties;

    @Autowired
    @Qualifier("agentInternalExecutor")
    private ExecutorService agentInternalExecutor;

    /**
     * 执行 AI Ops 告警分析流程。
     *
     * @param chatModel     大模型实例（会被包一层计量，不会修改原对象）
     * @param toolCallbacks MCP 工具回调
     * @return 运行结果，含报告与资源画像；被护栏终止时 {@code degradationReason} 非空
     */
    public AiOpsRunResult executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        AgentBudget budget = new AgentBudget(guardProperties);
        logger.info("开始执行 AI Ops 多 Agent 协作流程，预算上限: 迭代 {} 步 / LLM {} 次 / token {} / 耗时 {}s",
                guardProperties.getMaxIterations(),
                guardProperties.getMaxModelCalls(),
                guardProperties.getMaxTotalTokens(),
                guardProperties.getTimeoutSeconds());

        // 计量层：所有 Agent 都拿到这个包装过的模型，token 与调用次数因此必然被统计到
        ChatModel meteredModel = new MeteredChatModel(chatModel, budget);

        CompileConfig compileConfig = CompileConfig.builder()
                .recursionLimit(guardProperties.getMaxIterations())
                .build();

        ReactAgent plannerAgent = buildPlannerAgent(meteredModel, toolCallbacks, budget, compileConfig);
        ReactAgent executorAgent = buildExecutorAgent(meteredModel, toolCallbacks, budget, compileConfig);

        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(meteredModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .compileConfig(compileConfig)
                .executor(agentInternalExecutor)
                .build();

        String taskPrompt = "你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需诚实反馈无法完成的原因。";

        return runWithGuard(supervisorAgent, taskPrompt, budget);
    }

    /**
     * 带护栏的执行。
     *
     * <p><b>为什么直接在当前线程调用而不是 submit 到线程池再用 Future 超时</b>：
     * 当前线程本来就来自 {@code agentRequestExecutor}，再往同一个池里提交一个"等待型"任务
     * 会形成嵌套占用，池满时死锁。而且 {@code Future.cancel(true)} 对正在阻塞的 HTTP 调用
     * 本来也无效——它不会响应中断。
     *
     * <p>真正保证"到点就停"的是 {@link AgentBudget} 在<b>每一次模型调用和工具调用之前</b>
     * 检查已耗时：一旦超过 {@code agent.guard.timeout-seconds} 就抛异常终止。
     * 这是协作式超时，配合每次调用本身的 HTTP 超时（application.yml 里的 180s），
     * 最坏情况被限定为"多跑一次调用"，而不是无限运行。
     */
    private AiOpsRunResult runWithGuard(SupervisorAgent supervisorAgent, String taskPrompt, AgentBudget budget) {
        try {
            Optional<OverAllState> state = supervisorAgent.invoke(taskPrompt);

            if (state.isEmpty()) {
                return AiOpsRunResult.degraded("多 Agent 编排未返回有效结果", budget.snapshot());
            }

            Optional<String> report = extractFinalReport(state.get());
            if (report.isEmpty()) {
                return AiOpsRunResult.degraded("流程执行完成但未能生成最终报告", budget.snapshot());
            }

            logger.info("AI Ops 运行完成，资源消耗: {}", budget.snapshot());
            return AiOpsRunResult.success(report.get(), budget.snapshot());

        } catch (Exception e) {
            // 预算异常可能被框架包装过，需要沿 cause 链找
            AgentBudgetExceededException budgetExceeded = findCause(e, AgentBudgetExceededException.class);
            if (budgetExceeded != null) {
                logger.warn("AI Ops 运行被资源护栏终止: {}，资源消耗: {}",
                        budgetExceeded.getMessage(), budget.snapshot());
                return AiOpsRunResult.degraded(budgetExceeded.getMessage(), budget.snapshot());
            }

            logger.error("AI Ops 多 Agent 协作失败", e);
            return AiOpsRunResult.degraded("分析过程发生错误，请稍后重试", budget.snapshot());
        }
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        if (state == null) {
            return Optional.empty();
        }
        // 提取 Planner 最终输出（包含完整的告警分析报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            logger.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        }
        logger.warn("未能提取到 Planner 最终报告");
        return Optional.empty();
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(ChatModel chatModel, ToolCallback[] toolCallbacks,
                                         AgentBudget budget, CompileConfig compileConfig) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan")
                .interceptors(new LoopGuardToolInterceptor(budget),
                        new ToolResultTruncatingInterceptor(guardProperties.getMaxToolResultChars()))
                .compileConfig(compileConfig)
                .executor(agentInternalExecutor)
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(ChatModel chatModel, ToolCallback[] toolCallbacks,
                                          AgentBudget budget, CompileConfig compileConfig) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback")
                .interceptors(new LoopGuardToolInterceptor(budget),
                        new ToolResultTruncatingInterceptor(guardProperties.getMaxToolResultChars()))
                .compileConfig(compileConfig)
                .executor(agentInternalExecutor)
                .build();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需停止该方向并在最终报告的结论部分说明"无法完成"的原因。

                ## 最终报告输出要求（CRITICAL）

                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：

                ```
                # 告警分析报告

                ---

                ## 📋 活跃告警清单

                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |

                ---

                ## 🔍 告警根因分析1 - [告警名称]

                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]

                ### 症状描述
                [根据监控指标描述症状]

                ### 日志证据
                [引用查询到的关键日志]

                ### 根因结论
                [基于证据得出的根本原因]

                ---

                ## 🛠️ 处理方案执行1 - [告警名称]

                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]

                ### 处理建议
                [给出具体的处理建议]

                ### 预期效果
                [说明预期的效果]

                ---

                ## 🔍 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]

                ---

                ## 📊 结论

                ### 整体评估
                [总结所有告警的整体情况]

                ### 关键发现
                - [发现1]
                - [发现2]

                ### 后续建议
                1. [建议1]
                2. [建议2]

                ### 风险评估
                [评估当前风险等级和影响范围]
                ```

                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 告警分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过

                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。


                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "近1小时未见 error 日志，仅有 info",
                  "evidence": "...",
                  "nextHint": "建议转向高占用进程"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出完整的《告警分析报告》，格式必须严格为：
                   告警分析报告\n---\n# 告警处理详情\n## 活跃告警清单\n## 告警根因分析N\n## 处理方案执行N\n## 结论。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
                6. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。

                """;
    }
}
