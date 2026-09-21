package org.wowtools.dcu.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wowtools.dcu.stats.UserStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 可用用户注册表：apiKey -> 用户名 + 每用户最大并发。
 *
 * <p>数据源为 SQLite 的 user 表（见 {@link UserStore}），但热路径（每次 /v1/messages 鉴权）
 * 走内存缓存 {@code keyToUser}，避免每请求开 SQLite 连接。
 * 启动时加载一次；管理页增删改后调用 {@link #refresh()} 重新加载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRegistry {

    /** 每用户最大并发的缺省值（user 表 max_concurrency 列缺省也是 2）。 */
    public static final int DEFAULT_MAX_CONCURRENCY = 2;

    private final UserStore userStore;

    /** apiKey -> 用户信息（仅启用用户）。volatile 保证 refresh 后对所有线程可见。 */
    private volatile Map<String, User> keyToUser = new HashMap<>();

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 从 user 表重新加载启用用户到内存缓存。管理页增删改后调用。
     */
    public void refresh() {
        try {
            List<Map<String, Object>> rows = userStore.list();
            Map<String, User> m = new HashMap<>();
            for (Map<String, Object> r : rows) {
                Object enabled = r.get("enabled");
                boolean on = enabled instanceof Number n && n.intValue() == 1;
                if (!on) {
                    continue;
                }
                Object key = r.get("api_key");
                Object name = r.get("name");
                if (key != null && name != null) {
                    m.put(key.toString(), new User(name.toString(), maxConcurrencyOf(r)));
                }
            }
            this.keyToUser = m;
            log.info("用户缓存已刷新: {} 个启用用户", m.size());
        } catch (Exception e) {
            log.error("刷新用户缓存失败", e);
        }
    }

    /**
     * 校验 apiKey 是否有效（存在且启用）。纯内存查询。
     */
    public boolean isValid(String apiKey) {
        return apiKey != null && keyToUser.containsKey(apiKey);
    }

    /**
     * 由 apiKey 反查用户名（仅启用用户）；无效返回 null。纯内存查询。
     */
    public String nameOf(String apiKey) {
        User u = apiKey == null ? null : keyToUser.get(apiKey);
        return u == null ? null : u.name();
    }

    /**
     * 由 apiKey 反查该用户允许的最大并发（仅启用用户）；无效返回 {@link #DEFAULT_MAX_CONCURRENCY}。
     * 纯内存查询。
     */
    public int maxConcurrencyOf(String apiKey) {
        User u = apiKey == null ? null : keyToUser.get(apiKey);
        return u == null ? DEFAULT_MAX_CONCURRENCY : u.maxConcurrency();
    }

    /** 从 user 表行读 max_concurrency；缺失/非法回退默认值。 */
    private static int maxConcurrencyOf(Map<String, Object> row) {
        Object v = row.get("max_concurrency");
        if (v instanceof Number n && n.intValue() >= 1) {
            return n.intValue();
        }
        return DEFAULT_MAX_CONCURRENCY;
    }

    /** 缓存项：用户名 + 每用户最大并发。 */
    private record User(String name, int maxConcurrency) {
    }
}
