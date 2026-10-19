package com.smartoncall.controller;

import com.smartoncall.log.LogStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 本地日志库的运维端点。
 *
 * <p>用于演示和测试：注入一条日志 → 让 AIOps 在排查时查到它。
 * 这样不必依赖外部日志平台就能完整演示"告警 → 查日志 → 定位根因"的链路。
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogStore logStore;

    public LogController(LogStore logStore) {
        this.logStore = logStore;
    }

    /** 查询日志。所有过滤条件均可选 */
    @GetMapping
    public List<LogStore.LogEntry> query(@RequestParam(required = false) String service,
                                         @RequestParam(required = false) String level,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "20") int limit) {
        return logStore.query(service, level, null, keyword, limit);
    }

    /** 注入一条日志 */
    @PostMapping
    public LogStore.LogEntry add(@RequestBody LogStore.LogEntry entry) {
        return logStore.add(entry);
    }

    /** 清空日志（测试用） */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear() {
        logStore.clear();
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 当前日志条数 */
    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("count", logStore.size());
    }
}
