package com.smartoncall.service;

import com.smartoncall.agent.guard.AgentBudget;

import java.util.Optional;

/**
 * 一次 AIOps 运行的完整结果。
 *
 * <p>原实现只返回 {@code Optional<OverAllState>}，调用方无从知道这次运行
 * 是正常跑完、还是被护栏拦下的——只能看到"有没有报告"。现在把运行画像一并带出来，
 * 便于在响应里如实告知用户"分析未完成 + 原因"，也便于日志与后续的成本统计。
 *
 * @param report            最终报告；被终止或未产出时为空
 * @param budget            运行画像（LLM 调用次数、token、工具调用、耗时、终止原因）
 * @param degradationReason 非 null 表示本次运行被降级/终止，值是给用户看的原因
 */
public record AiOpsRunResult(
        Optional<String> report,
        AgentBudget.Snapshot budget,
        String degradationReason) {

    public boolean degraded() {
        return degradationReason != null;
    }

    public static AiOpsRunResult success(String report, AgentBudget.Snapshot budget) {
        return new AiOpsRunResult(Optional.of(report), budget, null);
    }

    public static AiOpsRunResult degraded(String reason, AgentBudget.Snapshot budget) {
        return new AiOpsRunResult(Optional.empty(), budget, reason);
    }
}
