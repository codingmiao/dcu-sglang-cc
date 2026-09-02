package org.wowtools.dcu.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wowtools.dcu.config.DcuConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SchemaMigrator 的三种库状态收敛：
 * <ul>
 *   <li>全新库：无表、无 schema_version → 执行 V1/V2/V3 → 版本 3。</li>
 *   <li>存量生产库：有表、无 schema_version（= 版本 0）→ 只补版本戳 + 跑 V2/V3 → 版本 3。</li>
 *   <li>已版本化库：schema_version=1 → 只跑 V2/V3 → 版本 3。</li>
 *   <li>幂等：再跑一次 → 仍版本 3，无变更。</li>
 * </ul>
 */
class SchemaMigratorTest {

    @TempDir
    Path tempDir;

    /** 在 tempDir 下写 V1/V2/V3 迁移文件，返回 file: 位置。 */
    private String writeMigrations() throws Exception {
        Path dir = tempDir.resolve("migration");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("V1__baseline.sql"), """
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
                CREATE INDEX IF NOT EXISTS idx_stat_ts ON request_stat(ts);
                CREATE TABLE IF NOT EXISTS user (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT UNIQUE NOT NULL,
                    api_key    TEXT UNIQUE NOT NULL,
                    enabled    INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER,
                    updated_at INTEGER
                );
                """);
        // V2：给 request_stat 加一列（模拟"后续表结构变更"）
        Files.writeString(dir.resolve("V2__add_latency.sql"), """
                ALTER TABLE request_stat ADD COLUMN latency_ms INTEGER;
                """);
        // V3：加一个索引
        Files.writeString(dir.resolve("V3__idx_latency.sql"), """
                CREATE INDEX IF NOT EXISTS idx_stat_latency ON request_stat(latency_ms);
                """);
        return "file:" + dir.toAbsolutePath() + "/V*.sql";
    }

    private Sqlite newSqlite() throws Exception {
        DcuConfiguration config = new DcuConfiguration();
        config.getStats().setSqlitePath(tempDir.resolve("stats.db").toString());
        Sqlite sqlite = new Sqlite(config);
        sqlite.init();
        return sqlite;
    }

    private int currentVersion(Sqlite sqlite) throws Exception {
        try (Connection c = sqlite.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private boolean hasColumn(Sqlite sqlite, String table, String column) throws Exception {
        try (Connection c = sqlite.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasIndex(Sqlite sqlite, String index) throws Exception {
        try (Connection c = sqlite.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='" + index + "'")) {
            return rs.next();
        }
    }

    @Test
    void freshDbUpgradesToLatest() throws Exception {
        Sqlite sqlite = newSqlite();
        new SchemaMigrator(sqlite, writeMigrations()).migrate();

        assertEquals(3, currentVersion(sqlite));
        assertTrue(hasColumn(sqlite, "request_stat", "latency_ms"), "V2 应加 latency_ms 列");
        assertTrue(hasIndex(sqlite, "idx_stat_latency"), "V3 应建 idx_stat_latency 索引");
        assertTrue(hasColumn(sqlite, "user", "api_key"), "V1 应建 user 表");
    }

    @Test
    void legacyProductionDbWithoutVersionTableUpgrades() throws Exception {
        Sqlite sqlite = newSqlite();
        // 模拟"存量生产库"：老代码建的表，但没有 schema_version 表
        try (Connection c = sqlite.open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE request_stat (id INTEGER PRIMARY KEY AUTOINCREMENT, log_id TEXT, "
                    + "user TEXT, model TEXT, stream INTEGER, input_tokens INTEGER, output_tokens INTEGER, "
                    + "cost INTEGER, stop_reason TEXT, success INTEGER, ts INTEGER)");
            st.execute("CREATE TABLE user (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, "
                    + "api_key TEXT UNIQUE NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER, updated_at INTEGER)");
            // 已有数据，升级后必须保留
            st.execute("INSERT INTO request_stat (log_id, user, ts) VALUES ('l1','alice',1700000000000)");
        }

        new SchemaMigrator(sqlite, writeMigrations()).migrate();

        assertEquals(3, currentVersion(sqlite));
        assertTrue(hasColumn(sqlite, "request_stat", "latency_ms"), "V2 应在存量表上加列");
        assertTrue(hasIndex(sqlite, "idx_stat_latency"), "V3 应建索引");
        // 存量数据保留
        try (Connection c = sqlite.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM request_stat")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "存量数据应保留");
        }
    }

    @Test
    void versionedDbAtV1OnlyAppliesPending() throws Exception {
        Sqlite sqlite = newSqlite();
        // 模拟"已版本化、停在 v1"的库：V1 的表已建好，schema_version=1
        try (Connection c = sqlite.open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE request_stat (id INTEGER PRIMARY KEY AUTOINCREMENT, log_id TEXT, "
                    + "user TEXT, model TEXT, stream INTEGER, input_tokens INTEGER, output_tokens INTEGER, "
                    + "cost INTEGER, stop_reason TEXT, success INTEGER, ts INTEGER)");
            st.execute("CREATE TABLE user (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, "
                    + "api_key TEXT UNIQUE NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER, updated_at INTEGER)");
            st.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)");
            st.execute("INSERT INTO schema_version (version) VALUES (1)");
        }

        new SchemaMigrator(sqlite, writeMigrations()).migrate();

        assertEquals(3, currentVersion(sqlite), "应从 v1 顺序执行 V2、V3 升到 v3");
        assertTrue(hasColumn(sqlite, "request_stat", "latency_ms"));
        assertTrue(hasIndex(sqlite, "idx_stat_latency"));
    }

    @Test
    void migrateIsIdempotent() throws Exception {
        Sqlite sqlite = newSqlite();
        String loc = writeMigrations();
        new SchemaMigrator(sqlite, loc).migrate();
        new SchemaMigrator(sqlite, loc).migrate(); // 再跑一次

        assertEquals(3, currentVersion(sqlite), "重复迁移不应改变版本");
        assertTrue(hasColumn(sqlite, "request_stat", "latency_ms"));
    }

    /**
     * 用真实 classpath 迁移（V1 baseline + V2 lines_changed）跑一遍，
     * 确认 V2 给 request_stat 加上了 lines_changed 列。
     */
    @Test
    void realClasspathMigrationsAddLinesChanged() throws Exception {
        Sqlite sqlite = newSqlite();
        new SchemaMigrator(sqlite).migrate(); // 公共构造器 = 默认 classpath 位置

        assertEquals(2, currentVersion(sqlite), "真实迁移应升到 v2");
        assertTrue(hasColumn(sqlite, "request_stat", "lines_changed"), "V2 应加 lines_changed 列");
        assertTrue(hasColumn(sqlite, "request_stat", "log_id"), "V1 应建 request_stat 表");
        assertTrue(hasColumn(sqlite, "user", "api_key"), "V1 应建 user 表");
    }
}
