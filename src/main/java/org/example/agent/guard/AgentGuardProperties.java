package org.example.agent.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 运行的资源护栏配置。
 *
 * <p>原实现把这些约束全写在了提示词里（"连续 3 次失败就停止"），靠 LLM 自觉遵守——
 * 那等于没有约束。这里把它们变成代码强制的硬上限。
 */
@ConfigurationProperties(prefix = "agent.guard")
public class AgentGuardProperties {

    /** Supervisor 图的最大递归步数。框架层硬限制，防止 Planner/Executor 无限互相调用 */
    private int maxIterations = 12;

    /** 单次运行允许的最大 LLM 调用次数 */
    private int maxModelCalls = 25;

    /** 单次运行允许的最大 token 消耗（输入+输出累计） */
    private int maxTotalTokens = 60_000;

    /** 单次运行允许的最大工具调用次数 */
    private int maxToolCalls = 40;

    /** 同一工具+同一参数重复调用多少次后开始拦截（返回提示让模型转向，不终止运行） */
    private int maxSameToolRepeats = 3;

    /**
     * 单个工具结果的最大字符数，超出则保留首尾、省略中间。
     *
     * <p>这是成本优化的关键杠杆：Agent 循环每一轮都会重发完整上下文，
     * 一份超长的日志结果会在后续每一次调用里重复计费。
     */
    private int maxToolResultChars = 3000;

    /** 单次运行的墙钟超时（秒）。到点强制取消，不再继续烧 token */
    private int timeoutSeconds = 180;

    /** 全局同时运行的 Agent 数量上限，超出直接拒绝而不是排队 */
    private int maxConcurrentRuns = 4;

    /** Agent 执行使用的线程池大小 */
    private int workerThreads = 8;

    /** 线程池队列容量 */
    private int workerQueueCapacity = 16;

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getMaxModelCalls() {
        return maxModelCalls;
    }

    public void setMaxModelCalls(int maxModelCalls) {
        this.maxModelCalls = maxModelCalls;
    }

    public int getMaxTotalTokens() {
        return maxTotalTokens;
    }

    public void setMaxTotalTokens(int maxTotalTokens) {
        this.maxTotalTokens = maxTotalTokens;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public int getMaxSameToolRepeats() {
        return maxSameToolRepeats;
    }

    public void setMaxSameToolRepeats(int maxSameToolRepeats) {
        this.maxSameToolRepeats = maxSameToolRepeats;
    }

    public int getMaxToolResultChars() {
        return maxToolResultChars;
    }

    public void setMaxToolResultChars(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    public void setMaxConcurrentRuns(int maxConcurrentRuns) {
        this.maxConcurrentRuns = maxConcurrentRuns;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getWorkerQueueCapacity() {
        return workerQueueCapacity;
    }

    public void setWorkerQueueCapacity(int workerQueueCapacity) {
        this.workerQueueCapacity = workerQueueCapacity;
    }
}
