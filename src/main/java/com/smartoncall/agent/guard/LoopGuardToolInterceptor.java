package com.smartoncall.agent.guard;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具调用护栏：拦住"同一个工具用同一套参数反复调用"这种钻牛角尖行为。
 *
 * <p>原实现把这件事交给提示词（"连续 3 次失败就停止"），但模型并不总是听话。
 * 这里改成代码强制：超过阈值后<b>不执行</b>该次调用，而是返回一条提示，
 * 让模型有机会换方向——比直接杀掉整次运行更划算。
 *
 * <p>注意这里只拦"重复"，总量超限（工具调用总数、token、耗时）由
 * {@link AgentBudget} 抛异常终止，两者的处理策略不同，原因见 {@link AgentBudget} 的类注释。
 */
public class LoopGuardToolInterceptor extends ToolInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LoopGuardToolInterceptor.class);

    private final AgentBudget budget;

    public LoopGuardToolInterceptor(AgentBudget budget) {
        this.budget = budget;
    }

    @Override
    public String getName() {
        return "loop-guard";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        AgentBudget.ToolDecision decision = budget.beforeToolCall(toolName, request.getArguments());

        if (decision.blocked()) {
            logger.warn("拦截重复工具调用 tool={} 原因={}", toolName, decision.reason());
            return ToolCallResponse.builder()
                    .toolName(toolName)
                    .toolCallId(request.getToolCallId())
                    .status("BLOCKED")
                    .content(decision.reason())
                    .build();
        }

        return handler.call(request);
    }
}
