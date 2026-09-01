package org.wowtools.dcu.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.wowtools.dcu.stats.JsonlLookupService;
import org.wowtools.dcu.stats.ServiceMetrics;
import org.wowtools.dcu.stats.StatsStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统计接口，供统计页面调用。数据源为 SQLite + jsonl 明细。
 */
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsStore statsStore;
    private final ServiceMetrics metrics;
    private final JsonlLookupService jsonlLookup;

    /** 服务整体概览（含当前状态） */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "online");
        m.put("data", statsStore.overview());
        m.put("service", metrics.snapshot());
        return m;
    }

    /** 按时间桶聚合（速度 / 趋势）。from 为时间窗起点（毫秒，可空） */
    @GetMapping("/by-time")
    public Map<String, Object> byTime(@RequestParam(defaultValue = "60") long bucketSeconds,
                                      @RequestParam(required = false) Long from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bucketSeconds", bucketSeconds);
        m.put("data", statsStore.byTimeBucket(bucketSeconds, from));
        return m;
    }

    /** 按模型聚合 */
    @GetMapping("/by-model")
    public Map<String, Object> byModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", statsStore.byModel());
        return m;
    }

    /** 用户分页表 */
    @GetMapping("/users")
    public Map<String, Object> users(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("page", page);
        m.put("size", size);
        m.put("data", statsStore.usersPaged(page, size));
        return m;
    }

    /** 某用户最近调用记录（分页） */
    @GetMapping("/users/{user}/records")
    public Map<String, Object> userRecords(@PathVariable String user,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", user);
        m.put("page", page);
        m.put("size", size);
        m.put("data", statsStore.userRecords(user, page, size));
        return m;
    }

    /** 某用户按时间序列（折线图）。from 为时间窗起点（毫秒，可空） */
    @GetMapping("/users/{user}/trend")
    public Map<String, Object> userTrend(@PathVariable String user,
                                        @RequestParam(defaultValue = "60") long bucketSeconds,
                                        @RequestParam(required = false) Long from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", user);
        m.put("bucketSeconds", bucketSeconds);
        m.put("data", statsStore.userTrend(user, bucketSeconds, from));
        return m;
    }

    /** 请求明细分页（可按时间窗 / 模型过滤），供服务统计页下钻 */
    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(required = false) Long from,
                                       @RequestParam(required = false) Long to,
                                       @RequestParam(required = false) String model,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("page", page);
        m.put("size", size);
        m.put("data", statsStore.recordsPaged(from, to, model, page, size));
        return m;
    }

    /** 按 logId 从 jsonl 明细里查完整 request/response */
    @GetMapping("/records/{logId}")
    public Map<String, Object> record(@PathVariable String logId) {
        Map<String, Object> m = new LinkedHashMap<>();
        JsonlLookupService.LookupResult r = jsonlLookup.find(logId);
        m.put("found", r.found());
        if (r.found()) {
            m.put("data", r.entry());
        } else {
            m.put("reason", r.reason());
        }
        return m;
    }
}
