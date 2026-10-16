package com.smartoncall.agent.guard;

/**
 * 运行超出资源护栏时抛出。
 *
 * <p>抛出它会中止 Agent 运行——这是刻意的：宁可返回一份"分析未完成 + 原因"，
 * 也不要让一个失控的循环把 token 配额烧光。
 *
 * <p>调用方应当捕获它，并从 {@link AgentBudget#terminationReason()} 取出可读的终止原因，
 * 拼进返回给用户的降级报告里。
 */
public class AgentBudgetExceededException extends RuntimeException {

    public AgentBudgetExceededException(String message) {
        super(message);
    }
}
