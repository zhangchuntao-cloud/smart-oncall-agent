package com.smartoncall.controller;

import com.smartoncall.alert.AlertSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 告警源的运维端点。
 *
 * <p>用于演示和测试：注入一条告警 → 让 AIOps 去分析它 → 解除告警。
 * 这样不必依赖外部 Prometheus 就能完整演示"告警触发 → 自动排查 → 生成报告"的链路。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertSource alertSource;

    public AlertController(AlertSource alertSource) {
        this.alertSource = alertSource;
    }

    /** 查询活跃告警。可选按告警名过滤 */
    @GetMapping
    public List<AlertSource.Alert> list(@RequestParam(required = false) String alertName) {
        return alertSource.query(alertName);
    }

    /** 注入一条告警 */
    @PostMapping
    public AlertSource.Alert create(@RequestBody AlertSource.Alert alert) {
        return alertSource.add(alert);
    }

    /** 解除一条告警 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String id) {
        boolean ok = alertSource.resolve(id);
        return ResponseEntity.ok(Map.of("success", ok, "id", id));
    }

    /** 清空全部告警（测试用） */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear() {
        alertSource.clear();
        return ResponseEntity.ok(Map.of("success", true));
    }
}
