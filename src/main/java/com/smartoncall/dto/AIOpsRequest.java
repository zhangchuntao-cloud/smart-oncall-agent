package com.smartoncall.dto;

import lombok.Data;

/**
 * AIOps 请求。
 *
 * <p>此前这个 DTO 定义了但**零引用**——接口 {@code POST /api/ai_ops} 不收任何参数，
 * 任务提示词硬编码在代码里。结果是"只能分析全部活跃告警"，无法指定要排查哪一条。
 *
 * <p>所有字段均为可选。不传请求体时保持原行为（分析全部活跃告警），向后兼容。
 */
@Data
public class AIOpsRequest {

    /** 只分析指定的告警（按告警名模糊匹配，如 HighCPUUsage） */
    private String alertName;

    /** 限定服务名，如 order-service */
    private String service;

    /**
     * 时间窗，如 "1h"、"30m"、"2h"。
     *
     * <p>用于日志查询与指标分析的范围限定。为空时由 Agent 自行决定。
     */
    private String timeRange;

    /** 用户的额外诉求，会原样拼进任务提示词，例如"重点排查是不是最近发的版本导致的" */
    private String userRequest;
}
