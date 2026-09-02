package org.wowtools.dcu.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.stats.ServiceMetrics;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * /v1/messages 并发门：限制同时转发到 sglang 的请求数，超出排队，排队满则拒绝。
 *
 * <p>两级信号量：
 * <ul>
 *   <li>{@code concurrency}（max-concurrency）：在途请求许可，持有到请求结束（含流式）。</li>
 *   <li>{@code queue}（queue-size）：排队许可，等待并发许可的请求占用。</li>
 * </ul>
 * 逻辑：先抢并发许可，抢到直接处理；抢不到则占一个排队许可（占不到即系统繁忙），
 * 再阻塞等待并发许可直到超时（超时即系统繁忙）。
 *
 * <p>许可在 {@link #release()} 中归还，调用方须在请求结束（含异常）时调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcurrencyGate {

    private final DcuConfiguration config;
    private final ServiceMetrics metrics;

    private Semaphore concurrency;
    private Semaphore queue;
    // clamp 后的配置值：active()/queued() 用它做减法，避免配置为 0 时出现负数（#10）
    private int maxConcurrency;
    private int queueSize;

    @PostConstruct
    public void init() {
        maxConcurrency = Math.max(1, config.getMaxConcurrency());
        queueSize = Math.max(0, config.getQueueSize());
        concurrency = new Semaphore(maxConcurrency, true);
        queue = new Semaphore(queueSize, true);
        log.info("并发门就绪: maxConcurrency={}, queueSize={}, queueWaitTimeout={}s",
                maxConcurrency, queueSize, config.getQueueWaitTimeout());
    }

    /**
     * 获取一个在途许可。
     *
     * @return 获取成功返回 true；系统繁忙（排队满或等待超时）返回 false
     */
    public boolean acquire() {
        // 快路径：直接拿到并发许可
        if (concurrency.tryAcquire()) {
            publishToMetrics();
            return true;
        }
        // 慢路径：占一个排队许可
        if (!queue.tryAcquire()) {
            publishToMetrics();
            return false;
        }
        try {
            long waitSec = Math.max(0, config.getQueueWaitTimeout());
            boolean got = concurrency.tryAcquire(waitSec, TimeUnit.SECONDS);
            if (!got) {
                log.warn("排队等待并发许可超时({}s)，返回系统繁忙", waitSec);
            }
            return got;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            // 无论是否拿到并发许可，排队许可都要归还
            queue.release();
            publishToMetrics();
        }
    }

    /**
     * 归还一个在途许可（请求结束时调用）。
     */
    public void release() {
        concurrency.release();
        publishToMetrics();
    }

    /** 当前在途请求数（已持有并发许可） */
    public int active() {
        return maxConcurrency - concurrency.availablePermits();
    }

    /** 当前排队请求数（已占排队许可、等待并发许可） */
    public int queued() {
        return queueSize - queue.availablePermits();
    }

    /** 供 /stats/overview 暴露的快照 */
    public void publishToMetrics() {
        metrics.setActive(active());
        metrics.setQueued(queued());
    }
}
