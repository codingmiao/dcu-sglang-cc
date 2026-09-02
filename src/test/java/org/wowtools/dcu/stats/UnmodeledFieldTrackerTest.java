package org.wowtools.dcu.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 #1.1 去重逻辑：同一字段路径集合出现 N 次 → 1 条记录且 count==N；
 * 不同集合各自成条；空路径不记录；超限新键忽略。
 */
class UnmodeledFieldTrackerTest {

    @Test
    void sameSetAccumulatesCount() {
        UnmodeledFieldTracker t = new UnmodeledFieldTracker();
        List<String> paths = List.of("tool.strict", "x_custom");
        assertTrue(t.track(paths, 1000, "log-1", "sample"));
        assertTrue(t.track(paths, 2000, "log-2", "sample"));
        assertTrue(t.track(paths, 3000, "log-3", "sample"));

        assertEquals(1, t.size());
        UnmodeledFieldTracker.Record r = t.snapshot().iterator().next();
        assertEquals(3, r.count);
        assertEquals("log-1", r.sampleLogId); // 首次的 logId
        assertEquals(1000, r.firstSeen);
        assertEquals(3000, r.lastSeen);
    }

    @Test
    void differentSetsAreDistinct() {
        UnmodeledFieldTracker t = new UnmodeledFieldTracker();
        assertTrue(t.track(List.of("a", "b"), 1, "l1", "s"));
        assertTrue(t.track(List.of("a", "c"), 2, "l2", "s"));
        assertEquals(2, t.size());
    }

    @Test
    void orderIndependent() {
        // 去重键是排序后的路径集合，顺序不同应视为同一键
        UnmodeledFieldTracker t = new UnmodeledFieldTracker();
        assertTrue(t.track(List.of("b", "a"), 1, "l1", "s"));
        assertTrue(t.track(List.of("a", "b"), 2, "l2", "s"));
        assertEquals(1, t.size());
        assertEquals(2, t.snapshot().iterator().next().count);
    }

    @Test
    void emptyPathsNotRecorded() {
        UnmodeledFieldTracker t = new UnmodeledFieldTracker();
        assertFalse(t.track(List.of(), 1, "l1", "s"));
        assertFalse(t.track(null, 1, "l1", "s"));
        assertEquals(0, t.size());
    }

    @Test
    void newKeyIgnoredBeyondLimit() {
        UnmodeledFieldTracker t = new UnmodeledFieldTracker();
        for (int i = 0; i < UnmodeledFieldTracker.MAX_KEYS; i++) {
            assertTrue(t.track(List.of("f" + i), i, "l" + i, "s"));
        }
        // 已满，新键忽略
        assertFalse(t.track(List.of("overflow"), 9999, "lo", "s"));
        assertEquals(UnmodeledFieldTracker.MAX_KEYS, t.size());
    }
}
