package org.wowtools.dcu.stats;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轻量服务状态指标（内存）：启动时间 / 在途请求数 / jsonl 队列深度。
 */
@Component
public class ServiceMetrics {

    private final long startTime = System.currentTimeMillis();
    private final AtomicInteger inFlight = new AtomicInteger();

    /** jsonl 队列深度（由 JsonlLogService 更新） */
    private volatile int queueDepth = 0;

    /** 并发门：在途请求数（由 ConcurrencyGate 更新） */
    private volatile int active = 0;

    /** 并发门：排队请求数（由 ConcurrencyGate 更新） */
    private volatile int queued = 0;

    @PostConstruct
    public void init() {
        // 仅用于记录启动时间
    }

    public void requestStarted() {
        inFlight.incrementAndGet();
    }

    public void requestFinished() {
        inFlight.decrementAndGet();
    }

    public void setQueueDepth(int depth) {
        this.queueDepth = depth;
    }

    public void setActive(int active) {
        this.active = active;
    }

    public void setQueued(int queued) {
        this.queued = queued;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startTime;
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int queueDepth() {
        return queueDepth;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "online");
        m.put("uptimeMillis", uptimeMillis());
        m.put("inFlight", inFlight());
        m.put("active", active);
        m.put("queued", queued);
        m.put("queueDepth", queueDepth());
        return m;
    }
}
