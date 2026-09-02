package org.wowtools.dcu.stats;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite schema 迁移器（手写版 Flyway；Flyway 社区版对 SQLite 无一等支持，
 * 且本库仅两张表、单实例部署，引框架属过度设计）。
 *
 * <p>迁移文件在 classpath {@code db/migration/}，命名 {@code V<目标版本>__<描述>.sql}
 * （如 {@code V1__baseline.sql}、{@code V2__add_xxx.sql}）。版本号 = 文件名前缀数字，
 * 表示"执行该文件后库应达到的版本"。
 *
 * <p>启动时（{@link #migrate()}，须在 {@link StatsStore}/{@link UserStore} 之前跑）：
 * <ol>
 *   <li>建 {@code schema_version} 表（若不存在）。</li>
 *   <li>读当前版本 N（无记录 = 0）。</li>
 *   <li>扫描 classpath 迁移文件，对目标版本 &gt; N 的按升序逐个执行：
 *       每个文件在 {@code autocommit=false} 事务里执行（SQLite 的 DDL 支持事务，
 *       失败整体回滚），成功后把 schema_version 更新为目标版本。</li>
 * </ol>
 *
 * <p>三种库状态都收敛到同一逻辑：
 * <ul>
 *   <li>全新库：N=0，执行 V1（baseline 全 IF NOT EXISTS）→ 版本 1。</li>
 *   <li>存量生产库（有表、无 schema_version）：N=0，执行 V1（全 no-op，只补版本戳）→ 版本 1。</li>
 *   <li>已版本化库：N=k，只执行目标版本 &gt; k 的文件（如生产 v1、本地 v3，则顺序跑 V2、V3）。</li>
 * </ul>
 *
 * <p>规则：已发布的迁移文件不可再改（改了生产不会重跑 → 版本漂移），只能追加新的。
 */
@Slf4j
@Component
public class SchemaMigrator {

    private static final String DEFAULT_LOCATION = "classpath*:db/migration/V*.sql";
    private static final Pattern VERSION_PATTERN = Pattern.compile("V(\\d+)__.*\\.sql");

    private final Sqlite sqlite;
    private final String location;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Autowired
    public SchemaMigrator(@Qualifier("sqlite") Sqlite sqlite) {
        this.sqlite = sqlite;
        this.location = DEFAULT_LOCATION;
    }

    /** 测试用：指定迁移文件位置（如 {@code file:tempdir/V*.sql}）。 */
    SchemaMigrator(Sqlite sqlite, String location) {
        this.sqlite = sqlite;
        this.location = location;
    }

    @PostConstruct
    public void migrate() throws Exception {
        final int startVersion = ensureVersionTableAndRead();
        List<Migration> pending = scanMigrations().stream()
                .filter(m -> m.version() > startVersion)
                .sorted(Comparator.comparingInt(Migration::version))
                .toList();
        if (pending.isEmpty()) {
            log.info("schema 已是最新版本: {}", startVersion);
            return;
        }
        int current = startVersion;
        for (Migration m : pending) {
            apply(m);
            log.info("schema 迁移: {} -> {} ({})", current, m.version(), m.resource().getFilename());
            current = m.version();
        }
    }

    /**
     * 建 schema_version 表（若不存在）并读当前版本；无记录返回 0。
     */
    private int ensureVersionTableAndRead() throws Exception {
        try (Connection c = sqlite.open()) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);");
                // 保证有一行可 UPDATE（空表 UPDATE 影响 0 行，版本会永远停在 0）
                st.execute("INSERT INTO schema_version (version) SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM schema_version);");
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * 扫描 classpath 迁移文件，解析目标版本号。
     */
    private List<Migration> scanMigrations() throws Exception {
        List<Migration> list = new ArrayList<>();
        for (Resource r : resolver.getResources(location)) {
            String name = r.getFilename();
            if (name == null) {
                continue;
            }
            Matcher m = VERSION_PATTERN.matcher(name);
            if (!m.matches()) {
                log.warn("忽略不符合 V<版本>__<描述>.sql 命名的迁移文件: {}", name);
                continue;
            }
            list.add(new Migration(Integer.parseInt(m.group(1)), r));
        }
        return list;
    }

    /**
     * 执行单个迁移文件：一个事务里逐条执行 + 更新 schema_version，失败回滚、版本不变（下次启动重试）。
     */
    private void apply(Migration m) throws Exception {
        String sql = readAll(m.resource());
        try (Connection c = sqlite.open()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String stmt : splitStatements(sql)) {
                    st.execute(stmt);
                }
                st.executeUpdate("UPDATE schema_version SET version = " + m.version());
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private String readAll(Resource r) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 按分号切分 SQL 语句（跳过空行与 {@code --} 注释行）。
     * 迁移文件里不要写含分号的字符串字面量 / 触发器 / 存储过程（本库用不到）。
     */
    private List<String> splitStatements(String sql) {
        StringBuilder body = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            body.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : body.toString().split(";")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private record Migration(int version, Resource resource) {
    }
}
