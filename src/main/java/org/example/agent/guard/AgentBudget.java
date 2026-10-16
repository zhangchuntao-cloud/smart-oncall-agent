package org.example.agent.guard;

import org.springframework.ai.chat.metadata.Usage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次 Agent 运行的资源账本。
 *
 * <p>每次 {@code /api/ai_ops} 请求创建一个实例，贯穿该次运行的始终，记录：
 * LLM 调用次数、累计 token、工具调用次数、同一工具重复调用的次数、以及已耗时。
 *
 * <p>两类超限的处理方式<b>刻意不同</b>：
 * <ul>
 *   <li><b>同一工具+同参数反复调用</b> → 不终止，只拦截并返回一条提示，让模型有机会换方向。
 *       这是"模型钻牛角尖"的常见形态，直接杀掉整次运行太浪费。</li>
 *   <li><b>总量超限（模型调用数 / token / 耗时）</b> → 抛 {@link AgentBudgetExceededException}
 *       立即终止。这类超限说明运行已经失控，继续下去只会烧更多钱。</li>
 * </ul>
 */
public class AgentBudget {

    private final AgentGuardProperties limits;
    private final long startedAtNanos = System.nanoTime();

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final Map<String, AtomicInteger> toolCallSignatures = new ConcurrentHashMap<>();

    /** 非 null 表示本次运行已被终止，值是可读原因 */
    private volatile String terminationReason;

    public AgentBudget(AgentGuardProperties limits) {
        this.limits = limits;
    }

    // ---------------------------------------------------------------- 模型侧

    /** 每次调用 LLM 之前调用。超限直接抛异常终止运行。 */
    public void beforeModelCall() {
        if (terminationReason != null) {
            throw new AgentBudgetExceededException(terminationReason);
        }
        if (elapsedSeconds() > limits.getTimeoutSeconds()) {
            throw terminate("运行超时（超过 " + limits.getTimeoutSeconds() + " 秒）");
        }
        // 先判断再自增：否则被拒绝的那一次也会被计入，导致画像里的次数比上限多 1
        if (modelCalls.get() >= limits.getMaxModelCalls()) {
            throw terminate("LLM 调用次数超过上限（" + limits.getMaxModelCalls() + " 次）");
        }
        modelCalls.incrementAndGet();
    }

    /** 记录一次 LLM 调用的 token 消耗。累计超预算抛异常终止。 */
    public void recordUsage(Usage usage) {
        if (usage == null || usage.getTotalTokens() == null) {
            return;
        }
        long total = totalTokens.addAndGet(usage.getTotalTokens());
        if (total > limits.getMaxTotalTokens()) {
            throw terminate("token 消耗超过预算（已用 " + total + "，上限 " + limits.getMaxTotalTokens() + "）");
        }
    }

    // ---------------------------------------------------------------- 工具侧

    /**
     * 每次工具调用之前调用。
     *
     * @return 放行，或拦截（附带给模型的提示语）
     */
    public ToolDecision beforeToolCall(String toolName, String arguments) {
        if (terminationReason != null) {
            throw new AgentBudgetExceededException(terminationReason);
        }
        if (elapsedSeconds() > limits.getTimeoutSeconds()) {
            throw terminate("运行超时（超过 " + limits.getTimeoutSeconds() + " 秒）");
        }

        int calls = toolCalls.incrementAndGet();
        if (calls > limits.getMaxToolCalls()) {
            throw terminate("工具调用次数超过上限（" + limits.getMaxToolCalls() + " 次）");
        }

        String signature = toolName + "#" + normalize(arguments);
        int repeats = toolCallSignatures
                .computeIfAbsent(signature, key -> new AtomicInteger())
                .incrementAndGet();

        if (repeats > limits.getMaxSameToolRepeats()) {
            return ToolDecision.blocked(String.format(
                    "工具 %s 已用相同参数调用 %d 次，结果不会变化。请换一个排查方向，"
                            + "或基于已有信息给出结论。",
                    toolName, repeats));
        }
        return ToolDecision.allowed();
    }

    /** 去掉空白后再比对，避免同一参数因格式差异被当成不同调用 */
    private String normalize(String arguments) {
        return arguments == null ? "" : arguments.replaceAll("\\s+", "");
    }

    // ---------------------------------------------------------------- 状态查询

    public boolean isTerminated() {
        return terminationReason != null;
    }

    public Optional<String> terminationReason() {
        return Optional.ofNullable(terminationReason);
    }

    public long elapsedSeconds() {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAtNanos);
    }

    /** 主动终止（例如外层墙钟超时触发） */
    public void markTerminated(String reason) {
        if (terminationReason == null) {
            terminationReason = reason;
        }
    }

    private AgentBudgetExceededException terminate(String reason) {
        markTerminated(reason);
        return new AgentBudgetExceededException(reason);
    }

    /** 供日志与响应使用的运行画像 */
    public Snapshot snapshot() {
        return new Snapshot(
                modelCalls.get(),
                totalTokens.get(),
                toolCalls.get(),
                elapsedSeconds(),
                terminationReason);
    }

    /** 一次工具调用的裁决结果 */
    public record ToolDecision(boolean blocked, String reason) {
        static ToolDecision allowed() {
            return new ToolDecision(false, null);
        }

        static ToolDecision blocked(String reason) {
            return new ToolDecision(true, reason);
        }
    }

    /** 运行画像快照 */
    public record Snapshot(
            int modelCalls,
            long totalTokens,
            int toolCalls,
            long elapsedSeconds,
            String terminationReason) {

        public boolean terminated() {
            return terminationReason != null;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modelCalls", modelCalls);
            map.put("totalTokens", totalTokens);
            map.put("toolCalls", toolCalls);
            map.put("elapsedSeconds", elapsedSeconds);
            map.put("terminationReason", terminationReason);
            return map;
        }

        @Override
        public String toString() {
            return String.format(
                    "llm调用=%d, token=%d, 工具调用=%d, 耗时=%ds%s",
                    modelCalls, totalTokens, toolCalls, elapsedSeconds,
                    terminated() ? ", 已终止: " + terminationReason : "");
        }
    }
}
