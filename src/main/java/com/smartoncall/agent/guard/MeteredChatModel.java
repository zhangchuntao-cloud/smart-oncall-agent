package com.smartoncall.agent.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;

/**
 * 给 {@link ChatModel} 套一层资源计量。
 *
 * <p><b>为什么包模型而不是用框架的 ModelInterceptor</b>：拦截器是否生效取决于
 * spring-ai-alibaba-graph 内部怎么调用模型——而 Supervisor 编排的子 Agent 走的是子图，
 * 拦截器能不能透传进去不好验证。包 {@code ChatModel} 则不同：<b>不管哪个 Agent、
 * 哪一层子图发起的调用，最终都必须经过这个对象</b>，计量和限额因此是确定的。
 *
 * <p>这也是"在最窄的必经之路上设卡"这个思路的又一次应用——和秒杀里
 * 把唯一索引放在 DB 层做最终兜底是同一个道理。
 */
public class MeteredChatModel implements ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(MeteredChatModel.class);

    private final ChatModel delegate;
    private final AgentBudget budget;

    public MeteredChatModel(ChatModel delegate, AgentBudget budget) {
        this.delegate = delegate;
        this.budget = budget;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        budget.beforeModelCall();
        ChatResponse response;
        try {
            response = delegate.call(prompt);
        } catch (NonTransientAiException e) {
            throw terminateOnNonTransient(e);
        }
        recordUsage(response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        budget.beforeModelCall();
        // 流式响应里 token 用量通常只在最后一个 chunk 给出，
        // 中间 chunk 的 usage 为 null，recordUsage 会自动跳过
        return delegate.stream(prompt)
                .doOnNext(this::recordUsage)
                .onErrorMap(NonTransientAiException.class, this::terminateOnNonTransient);
    }

    /**
     * 不可重试的错误（欠费、鉴权失败、参数非法等）立即终止整次运行。
     *
     * <p>否则框架会按重试策略反复调用，每一次都在 {@link AgentBudget} 上记一笔——
     * 一个必然失败的运行会白白吃掉整个模型调用预算，还让用户等很久才拿到错误。
     * 标记终止后，下一次 {@code beforeModelCall()} 会立刻拦下。
     *
     * <p><b>终止原因必须用户可读且不泄漏内部细节</b>：供应商的原始报文里带着
     * request_id、文档链接、有时还有账号信息，这些只该进服务端日志。
     */
    private NonTransientAiException terminateOnNonTransient(NonTransientAiException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage();
        logger.error("模型服务返回不可重试错误，终止本次运行: {}", detail);
        budget.markTerminated(describeNonTransient(detail));
        return e;
    }

    /** 把供应商错误翻译成用户可读、且不泄漏内部细节的说明 */
    private String describeNonTransient(String detail) {
        String lower = detail.toLowerCase();
        if (lower.contains("arrearage") || lower.contains("overdue-payment")) {
            return "模型服务调用失败：账户余额不足或已欠费";
        }
        if (lower.contains("401") || lower.contains("403")
                || lower.contains("unauthorized") || lower.contains("invalid") && lower.contains("key")) {
            return "模型服务调用失败：API Key 无效或无权限";
        }
        if (lower.contains("429") || lower.contains("throttl") || lower.contains("rate")) {
            return "模型服务调用失败：触发限流，请稍后重试";
        }
        return "模型服务返回不可恢复的错误，详情请查看服务端日志";
    }

    private void recordUsage(ChatResponse response) {
        Usage usage = extractUsage(response);
        if (usage == null || usage.getTotalTokens() == null) {
            // 诊断用：供应商没有回传用量时，token 预算就形同虚设。
            // 记一次 WARN 便于发现——不静默失败。
            logger.warn("模型响应未包含 token 用量，本次调用不计入预算。model={}",
                    response == null || response.getMetadata() == null
                            ? "unknown" : response.getMetadata().getModel());
            return;
        }
        // 逐次记录输入/输出分量，用于定位成本大头。
        // 只看累计值无法回答"是 system prompt 太胖，还是历史累积太快"，
        // 而这两个问题的解法完全不同。
        logger.info("[token] 本次输入={} 输出={} 累计={}/{}",
                usage.getPromptTokens(), usage.getCompletionTokens(),
                budget.snapshot().totalTokens() + usage.getTotalTokens(),
                budget.limitOfTotalTokens());
        budget.recordUsage(usage);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private Usage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }
}
