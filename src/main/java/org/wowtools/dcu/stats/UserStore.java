package org.wowtools.dcu.stats;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.service.UserRegistry;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户存储（SQLite）。
 * 支持动态增删、启用/禁用、重置 apiKey。
 * 启动时若表为空，把配置里的用户 seed 进去（向后兼容）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("schemaMigrator")
public class UserStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DcuConfiguration config;
    private final Sqlite sqlite;

    @PostConstruct
    public void init() throws Exception {
        // 建表 DDL 已收进 SchemaMigrator（db/migration/V1__baseline.sql），此处不再重复
        seedFromConfig();
    }

    /**
     * 表为空时，把配置里的用户导入。
     */
    private void seedFromConfig() {
        try (Connection c = sqlite.open()) {
            int count;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM user");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                count = rs.getInt(1);
            }
            if (count > 0 || config.getUsers() == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (DcuConfiguration.User u : config.getUsers()) {
                if (u.getName() == null || u.getApiKey() == null) {
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO user (name, api_key, enabled, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, u.getName());
                    ps.setString(2, u.getApiKey());
                    ps.setInt(3, 1);
                    ps.setLong(4, now);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
            }
            log.info("已从配置 seed 用户到 user 表");
        } catch (Exception e) {
            log.error("seed 用户失败", e);
        }
    }

    /**
     * 列出全部用户（管理页）。
     */
    public List<Map<String, Object>> list() {
        String sql = "SELECT id, name, api_key, enabled, max_concurrency, created_at, updated_at FROM user ORDER BY id";
        return query(sql);
    }

    /**
     * 新增用户。
     *
     * @param name          用户名
     * @param apiKey        为空则自动生成
     * @param maxConcurrency 每用户最大并发；null 或 &lt;1 取默认 2
     * @return 新用户的 id
     */
    public long create(String name, String apiKey, Integer maxConcurrency) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = generateKey();
        }
        int max = normalizeMaxConcurrency(maxConcurrency);
        long now = System.currentTimeMillis();
        try (Connection c = sqlite.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO user (name, api_key, enabled, max_concurrency, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, apiKey);
            ps.setInt(3, 1);
            ps.setInt(4, max);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    /**
     * 更新用户（改名 / 重置 key / 启用禁用 / 改并发上限）。null 字段表示不改。
     */
    public void update(long id, String name, String apiKey, Integer enabled, Integer maxConcurrency) throws Exception {
        // 占位符顺序：updated_at, [name], [api_key], [enabled], [max_concurrency], id
        StringBuilder sql = new StringBuilder("UPDATE user SET updated_at=?");
        List<Object> args = new ArrayList<>();
        args.add(System.currentTimeMillis());
        if (name != null) {
            sql.append(", name=?");
            args.add(name);
        }
        if (apiKey != null) {
            sql.append(", api_key=?");
            args.add(apiKey);
        }
        if (enabled != null) {
            sql.append(", enabled=?");
            args.add(enabled);
        }
        if (maxConcurrency != null) {
            sql.append(", max_concurrency=?");
            args.add(normalizeMaxConcurrency(maxConcurrency));
        }
        sql.append(" WHERE id=?");
        args.add(id);
        try (Connection c = sqlite.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (Object a : args) {
                if (a instanceof Integer) {
                    ps.setInt(i++, (Integer) a);
                } else if (a instanceof Long) {
                    ps.setLong(i++, (Long) a);
                } else {
                    ps.setString(i++, (String) a);
                }
            }
            ps.executeUpdate();
        }
    }

    /**
     * 重置 apiKey，返回新 key。
     */
    public String resetKey(long id) throws Exception {
        String key = generateKey();
        update(id, null, key, null, null);
        return key;
    }

    /**
     * 单事务更新：重置 apiKey 的同时改 name / enabled / max_concurrency（#13）。
     * 原先 resetKey + update 是两次独立连接/事务，中间失败会留下"key 已重置但其它字段没改"
     * 的中间态；这里合并成一条 UPDATE，要么全改要么全不改。
     *
     * @param newKey 新 apiKey（非空）
     * @return 新 key
     */
    public String updateWithKey(long id, String name, String newKey, Integer enabled, Integer maxConcurrency) throws Exception {
        // 占位符顺序：updated_at, api_key, [name], [enabled], [max_concurrency], id
        StringBuilder sql = new StringBuilder("UPDATE user SET updated_at=?, api_key=?");
        List<Object> args = new ArrayList<>();
        args.add(System.currentTimeMillis());
        args.add(newKey);
        if (name != null) {
            sql.append(", name=?");
            args.add(name);
        }
        if (enabled != null) {
            sql.append(", enabled=?");
            args.add(enabled);
        }
        if (maxConcurrency != null) {
            sql.append(", max_concurrency=?");
            args.add(normalizeMaxConcurrency(maxConcurrency));
        }
        sql.append(" WHERE id=?");
        args.add(id);
        try (Connection c = sqlite.open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (Object a : args) {
                if (a instanceof Integer) {
                    ps.setInt(i++, (Integer) a);
                } else if (a instanceof Long) {
                    ps.setLong(i++, (Long) a);
                } else {
                    ps.setString(i++, (String) a);
                }
            }
            ps.executeUpdate();
        }
        return newKey;
    }

    /**
     * 删除用户。
     */
    public void delete(long id) throws Exception {
        try (Connection c = sqlite.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM user WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * 归一化每用户最大并发：null 或 &lt;1 取默认 2。
     */
    private static int normalizeMaxConcurrency(Integer maxConcurrency) {
        if (maxConcurrency == null || maxConcurrency < 1) {
            return UserRegistry.DEFAULT_MAX_CONCURRENCY;
        }
        return maxConcurrency;
    }

    /**
     * 生成随机 apiKey：sk- + 32 位十六进制。
     */
    public static String generateKey() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("sk-");
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private List<Map<String, Object>> query(String sql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = sqlite.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        } catch (Exception e) {
            log.error("查询用户失败: {}", sql, e);
        }
        return rows;
    }
}
