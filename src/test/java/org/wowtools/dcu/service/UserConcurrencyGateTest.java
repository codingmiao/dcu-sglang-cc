package org.wowtools.dcu.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户级并发门：超限立即拒绝、release 后放行、limit 动态变化、计数归零清理。
 */
class UserConcurrencyGateTest {

    private final UserConcurrencyGate gate = new UserConcurrencyGate();

    @Test
    void rejectsBeyondLimit() {
        assertTrue(gate.acquire("alice", 2));
        assertTrue(gate.acquire("alice", 2));
        // 第 3 个超限，拒绝
        assertFalse(gate.acquire("alice", 2));
        assertEquals(2, gate.active("alice"));
    }

    @Test
    void releaseFreesSlot() {
        assertTrue(gate.acquire("alice", 1));
        assertFalse(gate.acquire("alice", 1));
        gate.release("alice");
        // 释放后可再占
        assertTrue(gate.acquire("alice", 1));
        assertEquals(1, gate.active("alice"));
    }

    @Test
    void usersAreIndependent() {
        assertTrue(gate.acquire("alice", 1));
        // alice 满，但 bob 不受影响
        assertTrue(gate.acquire("bob", 1));
        assertEquals(1, gate.active("alice"));
        assertEquals(1, gate.active("bob"));
    }

    @Test
    void limitChangeTakesEffectOnNextAcquire() {
        assertTrue(gate.acquire("alice", 1));
        // 上限仍是 1 时拒绝
        assertFalse(gate.acquire("alice", 1));
        // 管理页把上限调到 2：下一次 acquire 用新 limit 即放行
        assertTrue(gate.acquire("alice", 2));
        assertEquals(2, gate.active("alice"));
    }

    @Test
    void counterClearedWhenDrained() {
        assertTrue(gate.acquire("alice", 2));
        gate.release("alice");
        // 归零后 entry 应被清理，active 归 0
        assertEquals(0, gate.active("alice"));
    }

    @Test
    void releaseWithoutAcquireIsNoop() {
        // 未 acquire 就 release 不应抛异常，也不应产生负计数
        gate.release("ghost");
        assertEquals(0, gate.active("ghost"));
    }
}
