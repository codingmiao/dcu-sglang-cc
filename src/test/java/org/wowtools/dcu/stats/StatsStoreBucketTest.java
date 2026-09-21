package org.wowtools.dcu.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wowtools.dcu.config.DcuConfiguration;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 #2：时间桶宽度是"bucketSeconds 秒"而不是"bucketSeconds 毫秒"。
 * 同一分钟内的两条记录，byTimeBucket(60, null) 应合并为 1 个桶，
 * 且桶值（epoch 毫秒）满足 bucket % 60000 == 0。
 */
class StatsStoreBucketTest {

    @TempDir
    Path tempDir;

    private Sqlite sqlite;

    private StatsStore newStore() throws Exception {
        DcuConfiguration config = new DcuConfiguration();
        config.getStats().setSqlitePath(tempDir.resolve("stats.db").toString());
        sqlite = new Sqlite(config);
        sqlite.init();
        StatsStore store = new StatsStore(sqlite);
        // 直接建表并插入（绕过异步写队列，保证确定性）
        try (Connection c = sqlite.open(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS request_stat (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        log_id        TEXT,
                        user          TEXT,
                        model         TEXT,
                        stream        INTEGER,
                        input_tokens  INTEGER,
                        cache_read_input_tokens INTEGER,
                        output_tokens INTEGER,
                        lines_changed INTEGER,
                        cost          INTEGER,
                        stop_reason   TEXT,
                        success       INTEGER,
                        ts            INTEGER
                    );
                    """);
            // 同一分钟内的两条记录（相差 30 秒）
            st.execute("INSERT INTO request_stat (log_id, user, model, stream, input_tokens, output_tokens, cost, stop_reason, success, ts) "
                    + "VALUES ('l1','alice','m',0,10,20,100,'end_turn',1, 1700000000000)");
            st.execute("INSERT INTO request_stat (log_id, user, model, stream, input_tokens, output_tokens, cost, stop_reason, success, ts) "
                    + "VALUES ('l2','alice','m',0,10,20,100,'end_turn',1, 1700000030000)");
        }
        return store;
    }

    private void insert(String logId, int linesChanged) throws Exception {
        try (Connection c = sqlite.open(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO request_stat (log_id, user, model, stream, input_tokens, output_tokens, "
                    + "lines_changed, cost, stop_reason, success, ts) "
                    + "VALUES ('" + logId + "','alice','m',0,10,20," + linesChanged + ",100,'end_turn',1,1700000000000)");
        }
    }

    @Test
    void sameMinuteMergesIntoOneBucket() throws Exception {
        StatsStore store = newStore();
        List<Map<String, Object>> buckets = store.byTimeBucket(60, null);
        assertEquals(1, buckets.size(), "同一分钟的两条记录应合并为 1 个桶");
        long bucket = ((Number) buckets.get(0).get("bucket")).longValue();
        assertEquals(0, bucket % 60000, "桶值必须是 60000ms 的整数倍（epoch 毫秒）");
        assertEquals(2, ((Number) buckets.get(0).get("requests")).intValue());
    }

    @Test
    void overviewAndByModelSumLinesChanged() throws Exception {
        StatsStore store = newStore();
        // 再插两条：lines_changed 分别为 10、5（同一模型 m）
        insert("l3", 10);
        insert("l4", 5);

        Map<String, Object> ov = store.overview();
        // l1/l2 的 lines_changed 为 NULL（SUM 按 0 计），l3=10、l4=5 → 15
        assertEquals(15, ((Number) ov.get("total_lines_changed")).intValue(), "overview 应累加 lines_changed");

        List<Map<String, Object>> byModel = store.byModel();
        assertEquals(1, byModel.size());
        assertEquals(15, ((Number) byModel.get(0).get("lines_changed")).intValue(), "byModel 应累加 lines_changed");
    }
}
