package org.wowtools.dcu.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户级并发门：限制单个用户同时转发到 sglang 的请求数。
 *
 * <p>与全局 {@link ConcurrencyGate} 互补——全局门管"整个服务最多同时在途多少"，
 * 本门管"某个用户最多同时在途多少"。超限<b>立即拒绝</b>（不排队），由调用方返回 429。
 *
 * <p>实现：{@code ConcurrentHashMap<用户名, AtomicInteger>} 记每个用户的在途数。
 * 纯内存、无锁竞争，热路径开销可忽略。上限（limit）由调用方从 {@link UserRegistry}
 * 的内存缓存读出后传入，本门不感知配置，便于 limit 动态变化（管理页改完即生效）。
 *
 * <p>许可在 {@link #release(String)} 中归还，调用方须在请求结束（含异常）时调用。
 */
@Slf4j
@Component
public class UserConcurrencyGate {

    /** 用户名 -> 在途请求计数。计数归零时移除 entry，防止用户数无限增长。 */
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /**
     * 为某用户占一个在途许可。
     *
     * @param user  用户名
     * @param limit 该用户允许的最大并发（&lt;1 视为 1）
     * @return 未超限返回 true；已超限返回 false（调用方应拒绝请求）
     */
    public boolean acquire(String user, int limit) {
        int max = Math.max(1, limit);
        AtomicInteger counter = inFlight.computeIfAbsent(user, k -> new AtomicInteger());
        int now = counter.incrementAndGet();
        if (now > max) {
            // 超限：回退计数，拒绝
            counter.decrementAndGet();
            if (counter.get() == 0) {
                inFlight.remove(user, counter);
            }
            return false;
        }
        return true;
    }

    /**
     * 归还一个在途许可（请求结束时调用）。
     */
    public void release(String user) {
        AtomicInteger counter = inFlight.get(user);
        if (counter == null) {
            return;
        }
        int now = counter.decrementAndGet();
        if (now <= 0) {
            // 归零移除，防内存泄漏；remove 带期望值，避免误删并发新建的 counter
            inFlight.remove(user, counter);
        }
    }

    /** 某用户当前在途请求数（供观测/测试）。 */
    public int active(String user) {
        AtomicInteger counter = inFlight.get(user);
        return counter == null ? 0 : Math.max(0, counter.get());
    }
}
