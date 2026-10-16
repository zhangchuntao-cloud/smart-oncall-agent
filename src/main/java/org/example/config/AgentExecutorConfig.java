package org.example.config;

import org.example.agent.guard.AgentGuardProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 执行用的有界线程池。
 *
 * <p>原实现用的是 {@code Executors.newCachedThreadPool()}——线程数无上限、队列无上限、
 * 且从不关闭。每个 {@code /api/ai_ops} 请求都会占一个线程跑完整的多次 LLM 调用，
 * 所以 N 个并发请求就是 N 个多 Agent 运行同时烧配额。任何一个客户端都能把线程和
 * DashScope 配额打满。
 *
 * <p><b>为什么是两个池而不是一个</b>：外层请求线程会阻塞等待 Agent 内部图执行的完成。
 * 如果两者共用一个有界池，池满时就会出现"外层任务占着线程等内层任务、内层任务排队等线程"
 * 的经典线程池死锁。拆成两个独立池，让等待关系不构成环。
 *
 * <p>拒绝策略都选 Abort：<b>宁可快速失败让调用方返回"系统繁忙"，也不要排队堆积</b>——
 * 排队的请求最终会因超时全部失败，还白白占着内存。
 */
@Configuration
public class AgentExecutorConfig {

    /** 承载 HTTP 请求侧的 Agent 任务。与 {@link #agentInternalExecutor} 隔离以避免死锁 */
    @Bean(name = "agentRequestExecutor", destroyMethod = "shutdown")
    public ExecutorService agentRequestExecutor(AgentGuardProperties properties) {
        return buildPool("agent-request-", properties.getWorkerThreads(),
                properties.getWorkerQueueCapacity());
    }

    /** 供 Agent 图内部调度使用。比请求池大一些，因为图执行会产生并发子任务 */
    @Bean(name = "agentInternalExecutor", destroyMethod = "shutdown")
    public ExecutorService agentInternalExecutor(AgentGuardProperties properties) {
        return buildPool("agent-internal-", properties.getWorkerThreads() * 2,
                properties.getWorkerQueueCapacity() * 2);
    }

    private ExecutorService buildPool(String threadPrefix, int threads, int queueCapacity) {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                threads,
                threads,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
