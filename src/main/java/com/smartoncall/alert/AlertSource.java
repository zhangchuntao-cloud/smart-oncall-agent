package com.smartoncall.alert;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内置告警源。
 *
 * <p><b>为什么需要它</b>：这个项目的 AIOps 模块依赖 Prometheus 的 {@code /api/v1/alerts}
 * 作为告警输入。但本地环境通常没有 Prometheus，而配置又是 {@code mock-enabled: false}，
 * 结果是**每次查询告警都失败**——分析模块拿不到输入，跑出来的报告只能是模型的臆测。
 *
 * <p>这个类提供一个**真实可用的告警源**：内存存储 + REST 写入端点 + 预置的故障场景数据。
 * 它不是 mock——数据是真实存在于本系统里、可被查询、可被修改的，只是数据源从
 * "Prometheus" 换成了"本系统自己的告警表"。演示时可以随时注入新的告警。
 *
 * <p><b>为什么不直接起一个 Prometheus</b>：Prometheus 的价值在于长期指标存储与
 * 告警规则求值，而我们要的只是"当前有哪些活跃告警"这一个能力。为它额外维护一个
 * 容器 + 配置文件 + 规则，性价比不高。自己实现反而可控——能按需构造各种故障场景。
 *
 * <p>生产环境应当换成真正的告警源（Prometheus / 云监控 / 自建告警平台），
 * 接口保持一致即可。
 */
@Component
public class AlertSource {

    private static final Logger logger = LoggerFactory.getLogger(AlertSource.class);

    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<>();

    public AlertSource() {
        seedDefaults();
    }

    /**
     * 预置几个典型的故障场景。
     *
     * <p>这些场景与 {@code aiops-docs/} 里的排障手册一一对应
     * （CPU 高、内存高、磁盘满、服务不可用、响应慢），
     * 这样 RAG 检索才有可匹配的文档，整条链路才讲得通。
     */
    private void seedDefaults() {
        long now = Instant.now().getEpochSecond();
        add(new Alert("HighCPUUsage", "critical", "order-service",
                "CPU usage on order-service exceeded 90% for 5 minutes",
                now - 900, 92.5, Map.of("instance", "order-service-7d9f", "region", "ap-guangzhou")));
        add(new Alert("HighMemoryUsage", "warning", "payment-service",
                "Memory usage on payment-service exceeded 85%",
                now - 600, 87.3, Map.of("instance", "payment-service-2b4c", "region", "ap-guangzhou")));
        add(new Alert("SlowResponse", "warning", "order-service",
                "P99 latency of /api/order/query exceeded 2s",
                now - 300, 2350, Map.of("instance", "order-service-7d9f", "endpoint", "/api/order/query")));
        logger.info("告警源已预置 {} 条活跃告警", activeAlerts.size());
    }

    /** 新增告警。id 由调用方提供或自动生成 */
    public Alert add(Alert alert) {
        if (alert.getId() == null || alert.getId().isBlank()) {
            alert.setId(UUID.randomUUID().toString());
        }
        if (alert.getState() == null || alert.getState().isBlank()) {
            alert.setState("firing");
        }
        if (alert.getActiveAt() <= 0) {
            alert.setActiveAt(Instant.now().getEpochSecond());
        }
        activeAlerts.put(alert.getId(), alert);
        logger.info("新增告警 id={} name={} service={}", alert.getId(), alert.getAlertName(), alert.getService());
        return alert;
    }

    /** 按告警名过滤查询。name 为 null 或空时返回全部 */
    public List<Alert> query(String alertName) {
        List<Alert> all = new ArrayList<>(activeAlerts.values());
        if (alertName == null || alertName.isBlank()) {
            return all;
        }
        String keyword = alertName.toLowerCase();
        return all.stream()
                .filter(a -> a.getAlertName() != null && a.getAlertName().toLowerCase().contains(keyword))
                .collect(Collectors.toList());
    }

    /** 解除某条告警（模拟恢复到正常状态） */
    public boolean resolve(String id) {
        Alert removed = activeAlerts.remove(id);
        if (removed != null) {
            logger.info("告警已解除 id={} name={}", id, removed.getAlertName());
            return true;
        }
        return false;
    }

    /** 清空所有告警（测试用） */
    public void clear() {
        activeAlerts.clear();
        logger.info("已清空所有告警");
    }

    /**
     * 一条告警。字段刻意与 Prometheus 的 {@code /api/v1/alerts} 对齐，
     * 这样切换告警源时下游无需改动。
     */
    @Data
    public static class Alert {
        private String id;
        /** 告警名，例如 HighCPUUsage */
        private String alertName;
        /** 级别：critical / warning / info */
        private String level;
        /** 受影响的服务名 */
        private String service;
        /** 告警描述 */
        private String description;
        /** firing / resolved */
        private String state;
        /** 首次触发时间（epoch 秒） */
        private long activeAt;
        /** 当前指标值 */
        private double value;
        /** 附加标签 */
        private Map<String, String> labels;

        public Alert() {
        }

        public Alert(String alertName, String level, String service, String description,
                     long activeAt, double value, Map<String, String> labels) {
            this.alertName = alertName;
            this.level = level;
            this.service = service;
            this.description = description;
            this.activeAt = activeAt;
            this.value = value;
            this.labels = labels;
        }

        /** 已持续时间，供报告展示 */
        public String getDuration() {
            long seconds = Math.max(0, Instant.now().getEpochSecond() - activeAt);
            long minutes = seconds / 60;
            if (minutes >= 60) {
                return (minutes / 60) + "小时" + (minutes % 60) + "分钟";
            }
            return minutes + "分钟";
        }
    }
}
