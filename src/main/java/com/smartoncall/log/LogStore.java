package com.smartoncall.log;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

/**
 * 本地日志库。
 *
 * <p><b>为什么需要它</b>：{@code QueryLogsTools} 的真实模式原本是个桩——
 * 直接返回"CLS 真实查询尚未实现，请启用 mock 模式"。这意味着 AIOps 的
 * **两大输入源（告警、日志）里，日志这一路是完全空的**，报告只能靠模型猜。
 *
 * <p>这个类提供一个可写、可查、可演示的日志源：
 * <ul>
 *   <li>通过 {@code POST /api/logs} 注入日志（演示时用来构造故障现场）</li>
 *   <li>按服务、级别、时间窗、关键词过滤查询</li>
 *   <li>预置了与 {@code aiops-docs/} 排障手册对应的故障日志</li>
 * </ul>
 *
 * <p>生产环境应当接真实的日志系统（腾讯云 CLS / ELK 等）。
 * 这里自己实现，是因为为了演示一个能力去额外维护一个 MCP 服务或日志后端性价比不高，
 * 而且自己实现能更清楚地讲"参数校验、过滤、结果截断"这些细节。
 */
@Component
public class LogStore {

    private static final Logger logger = LoggerFactory.getLogger(LogStore.class);

    /** 用时间戳做 key，天然按时间有序，便于范围查询 */
    private final ConcurrentSkipListMap<Long, LogEntry> logs = new ConcurrentSkipListMap<>();

    public LogStore() {
        seedDefaults();
    }

    /** 预置与排障手册对应的故障日志 */
    private void seedDefaults() {
        long now = Instant.now().getEpochSecond();

        // CPU 高的现场：全量同步任务未分页，遍历大量记录
        add(new LogEntry("order-service", "ERROR", now - 1200,
                "Full data sync task started, batchSize=0 (unpaged), total=10000000"));
        add(new LogEntry("order-service", "WARN", now - 1100,
                "Thread pool [data-sync] queue size 512/512, tasks rejected"));
        add(new LogEntry("order-service", "ERROR", now - 900,
                "CPU usage 92.5% exceeds threshold, top process: data-sync-service"));

        // 内存高的现场
        add(new LogEntry("payment-service", "WARN", now - 800,
                "Heap usage 87.3%, GC pause 420ms, full GC count=12 in last 5min"));
        add(new LogEntry("payment-service", "ERROR", now - 700,
                "Possible memory leak: cache entry count grows unbounded, size=2.3M"));

        // 响应慢的现场：下游依赖超时
        add(new LogEntry("order-service", "ERROR", now - 400,
                "Downstream call timeout: POST http://inventory-service/stock/deduct, cost=2100ms, limit=1000ms"));
        add(new LogEntry("order-service", "WARN", now - 350,
                "P99 latency 2350ms exceeded SLO 2000ms on /api/order/query"));

        logger.info("日志库已预置 {} 条日志", logs.size());
    }

    /** 写入一条日志 */
    public LogEntry add(LogEntry entry) {
        if (entry.getId() == null || entry.getId().isBlank()) {
            entry.setId(UUID.randomUUID().toString());
        }
        if (entry.getTimestamp() <= 0) {
            entry.setTimestamp(Instant.now().getEpochSecond());
        }
        // 时间戳相同时用纳秒偏移避免覆盖
        long key = entry.getTimestamp() * 1000 + (logs.size() % 1000);
        logs.put(key, entry);
        return entry;
    }

    /**
     * 查询日志。
     *
     * @param service   服务名，null 表示不限
     * @param level     级别（ERROR / WARN / INFO），null 表示不限
     * @param sinceSec  起始时间（epoch 秒），null 表示不限
     * @param keyword   关键词，在消息里做大小写不敏感包含匹配，null 表示不限
     * @param limit     返回条数上限，按时间**倒序**取最近的
     */
    public List<LogEntry> query(String service, String level, Long sinceSec, String keyword, int limit) {
        List<LogEntry> matched = logs.values().stream()
                .filter(e -> service == null || service.equalsIgnoreCase(e.getService()))
                .filter(e -> level == null || level.equalsIgnoreCase(e.getLevel()))
                .filter(e -> sinceSec == null || e.getTimestamp() >= sinceSec)
                .filter(e -> keyword == null || keyword.isBlank()
                        || (e.getMessage() != null
                        && e.getMessage().toLowerCase().contains(keyword.toLowerCase())))
                .sorted(Comparator.comparingLong(LogEntry::getTimestamp).reversed())
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
        return new ArrayList<>(matched);
    }

    /** 清空（测试用） */
    public void clear() {
        logs.clear();
        logger.info("日志库已清空");
    }

    public int size() {
        return logs.size();
    }

    /** 已知服务名集合，供查询工具从自然语言里识别服务 */
    public java.util.Set<String> knownServices() {
        return logs.values().stream()
                .map(LogEntry::getService)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 一条日志。字段与常见日志平台对齐，便于将来替换数据源。
     */
    @Data
    public static class LogEntry {
        private String id;
        /** 来源服务 */
        private String service;
        /** ERROR / WARN / INFO */
        private String level;
        /** 时间戳（epoch 秒） */
        private long timestamp;
        /** 日志内容 */
        private String message;
        /** 附加字段，如 traceId、pod 名 */
        private Map<String, String> fields;

        public LogEntry() {
        }

        public LogEntry(String service, String level, long timestamp, String message) {
            this.service = service;
            this.level = level;
            this.timestamp = timestamp;
            this.message = message;
        }

        /** 供报告展示的可读时间 */
        public String getTime() {
            return Instant.ofEpochSecond(timestamp).toString();
        }
    }
}
