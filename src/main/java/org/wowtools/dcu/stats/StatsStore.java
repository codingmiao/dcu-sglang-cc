package org.wowtools.dcu.stats;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SQLite 统计存储。
 * 存可聚合的统计字段（见 {@link RequestStat}），供统计接口查询。
 * 连接由 {@link Sqlite} 统一提供（WAL + busy_timeout，线程安全）。
 *
 * <p>写入走内存队列 + 后台批量落库（对齐 {@link JsonlLogService}）：
 * 业务线程只入队，后台单线程攒批后在一个事务里批量 INSERT，
 * 消除每请求开连接 + 单行 autocommit 的开销。统计页最多滞后一个刷新周期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsStore {

    private static final int BATCH_SIZE = 100;
    private static final long FLUSH_INTERVAL_MS = 1000;
    private static final int QUEUE_CAPACITY = 10000;

    private final Sqlite sqlite;

    private final BlockingQueue<RequestStat> statQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running = true;
    private Thread consumerThread;

    @PostConstruct
    public void init() throws Exception {
        try (Connection c = sqlite.open(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS request_stat (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        log_id        TEXT,
                        user          TEXT,
                        model         TEXT,
                        stream        INTEGER,
                        input_tokens  INTEGER,
                        output_tokens INTEGER,
                        cost          INTEGER,
                        stop_reason   TEXT,
                        success       INTEGER,
                        ts            INTEGER
                    );
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_stat_ts ON request_stat(ts);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_stat_user ON request_stat(user);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_stat_log ON request_stat(log_id);");
        }
        consumerThread = Thread.ofVirtual().name("stat-writer").start(this::consumeStats);
        log.info("SQLite 统计库就绪，批量写入线程已启动");
    }

    /**
     * 入队一条统计明细（业务线程调用，非阻塞）。
     */
    public void insert(RequestStat s) {
        if (!running) {
            return;
        }
        boolean ok = statQueue.offer(s);
        if (!ok) {
            log.warn("统计队列已满，丢弃: logId={}", s.getLogId());
        }
    }

    /**
     * 后台消费：攒批后在一个事务里批量写入。
     */
    private void consumeStats() {
        List<RequestStat> batch = new ArrayList<>(BATCH_SIZE);
        long lastFlush = System.currentTimeMillis();
        while (running || !statQueue.isEmpty()) {
            try {
                RequestStat s = statQueue.poll(1, TimeUnit.SECONDS);
                if (s != null) {
                    batch.add(s);
                }
                long now = System.currentTimeMillis();
                boolean flush = batch.size() >= BATCH_SIZE
                        || (now - lastFlush >= FLUSH_INTERVAL_MS && !batch.isEmpty())
                        || (!running && !batch.isEmpty());
                if (flush) {
                    flushBatch(batch);
                    batch.clear();
                    lastFlush = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("统计消费线程异常", e);
            }
        }
        if (!batch.isEmpty()) {
            flushBatch(batch);
        }
    }

    /**
     * 一个事务批量写入一批统计明细。
     */
    private void flushBatch(List<RequestStat> batch) {
        if (batch.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO request_stat
                (log_id, user, model, stream, input_tokens, output_tokens, cost, stop_reason, success, ts)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection c = sqlite.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (RequestStat s : batch) {
                    ps.setString(1, s.getLogId());
                    ps.setString(2, s.getUser());
                    ps.setString(3, s.getModel());
                    ps.setInt(4, s.isStream() ? 1 : 0);
                    ps.setInt(5, s.getInputTokens());
                    ps.setInt(6, s.getOutputTokens());
                    ps.setLong(7, s.getCost());
                    ps.setString(8, s.getStopReason());
                    ps.setInt(9, s.isSuccess() ? 1 : 0);
                    ps.setLong(10, s.getTs());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (Exception e) {
            log.error("批量写入统计明细失败, size={}", batch.size(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (consumerThread != null) {
            try {
                consumerThread.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 服务整体概览。
     */
    public Map<String, Object> overview() {
        String sql = """
                SELECT COUNT(*)            AS total_requests,
                       SUM(input_tokens)   AS total_input_tokens,
                       SUM(output_tokens) AS total_output_tokens,
                       AVG(cost)           AS avg_cost,
                       SUM(CASE WHEN success=1 THEN 1 ELSE 0 END) AS success_count,
                       MAX(ts)             AS last_ts
                FROM request_stat
                """;
        List<Map<String, Object>> rows = query(sql);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    /**
     * 按时间桶聚合请求数、token、耗时、每秒输出 token（用于趋势折线图）。
     * from 为时间窗起点（毫秒，可空）；tps = 平均每秒输出 token 数。
     */
    public List<Map<String, Object>> byTimeBucket(long bucketSeconds, Long from) {
        StringBuilder sql = new StringBuilder("""
                SELECT (ts / %d) * %d AS bucket,
                       COUNT(*)        AS requests,
                       SUM(input_tokens)  AS input_tokens,
                       SUM(output_tokens) AS output_tokens,
                       AVG(cost)         AS avg_cost,
                       AVG(CASE WHEN cost > 0 THEN output_tokens * 1000.0 / cost END) AS tps
                FROM request_stat
                WHERE 1=1
                """.formatted(bucketSeconds, bucketSeconds));
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND ts >= ?");
            args.add(from);
        }
        sql.append(" GROUP BY bucket ORDER BY bucket");
        return query(sql.toString(), args.toArray());
    }

    /**
     * 按模型聚合请求数与 token。
     */
    public List<Map<String, Object>> byModel() {
        String sql = """
                SELECT model,
                       COUNT(*)            AS requests,
                       SUM(input_tokens)   AS input_tokens,
                       SUM(output_tokens)  AS output_tokens,
                       AVG(cost)           AS avg_cost
                FROM request_stat
                GROUP BY model
                ORDER BY requests DESC
                """;
        return query(sql);
    }

    /**
     * 用户分页表：各用户请求数、token、耗时、成功率、最近活跃。
     */
    public List<Map<String, Object>> usersPaged(int page, int size) {
        String sql = """
                SELECT user,
                       COUNT(*)            AS requests,
                       SUM(input_tokens)   AS input_tokens,
                       SUM(output_tokens)  AS output_tokens,
                       AVG(cost)           AS avg_cost,
                       SUM(CASE WHEN success=1 THEN 1 ELSE 0 END) AS success_count,
                       MAX(ts)             AS last_ts
                FROM request_stat
                GROUP BY user
                ORDER BY (input_tokens + output_tokens) DESC
                LIMIT ? OFFSET ?
                """;
        return query(sql, size, page * size);
    }

    /**
     * 某用户最近调用记录（分页，ts 倒序）。
     */
    public List<Map<String, Object>> userRecords(String user, int page, int size) {
        String sql = """
                SELECT log_id, model, stream, input_tokens, output_tokens,
                       cost, stop_reason, success, ts
                FROM request_stat
                WHERE user = ?
                ORDER BY ts DESC
                LIMIT ? OFFSET ?
                """;
        return query(sql, user, size, page * size);
    }

    /**
     * 某用户按时间序列（折线图）。from 为时间窗起点（毫秒，可空）。
     */
    public List<Map<String, Object>> userTrend(String user, long bucketSeconds, Long from) {
        StringBuilder sql = new StringBuilder("""
                SELECT (ts / %d) * %d AS bucket,
                       COUNT(*)        AS requests,
                       SUM(input_tokens)  AS input_tokens,
                       SUM(output_tokens) AS output_tokens,
                       AVG(cost)         AS avg_cost,
                       AVG(CASE WHEN cost > 0 THEN output_tokens * 1000.0 / cost END) AS tps
                FROM request_stat
                WHERE user = ?
                """.formatted(bucketSeconds, bucketSeconds));
        List<Object> args = new ArrayList<>();
        args.add(user);
        if (from != null) {
            sql.append(" AND ts >= ?");
            args.add(from);
        }
        sql.append(" GROUP BY bucket ORDER BY bucket");
        return query(sql.toString(), args.toArray());
    }

    /**
     * 请求明细分页（可按时间窗 / 模型过滤），供服务统计页下钻。
     */
    public List<Map<String, Object>> recordsPaged(Long from, Long to, String model, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT log_id, user, model, stream, input_tokens, output_tokens, " +
                "cost, stop_reason, success, ts FROM request_stat WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND ts >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND ts < ?");
            args.add(to);
        }
        if (model != null && !model.isBlank()) {
            sql.append(" AND model = ?");
            args.add(model);
        }
        sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(page * size);
        return query(sql.toString(), args.toArray());
    }

    /**
     * 按 logId 查一条统计记录（用于判断该记录是否存在，配合 jsonl 查找）。
     */
    public Map<String, Object> byLogId(String logId) {
        String sql = "SELECT * FROM request_stat WHERE log_id = ?";
        List<Map<String, Object>> rows = query(sql, logId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> query(String sql, Object... args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = sqlite.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof Integer) {
                    ps.setInt(i + 1, (Integer) a);
                } else if (a instanceof Long) {
                    ps.setLong(i + 1, (Long) a);
                } else {
                    ps.setString(i + 1, (String) a);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            log.error("统计查询失败: {}", sql, e);
        }
        return rows;
    }
}
