package org.wowtools.dcu.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.service.UserRegistry;
import org.wowtools.dcu.stats.UserStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员接口：登录 / 登出 / 用户维护。
 * 登录态用 HttpSession（不引额外依赖）。管理员账号来自配置 dcu.admin。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final String SESSION_KEY = "dcu.admin";
    // 登录失败限制（#15）：同一用户名连续失败 N 次后锁定 LOCK_MS
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 5 * 60 * 1000L;

    private final DcuConfiguration config;
    private final UserStore userStore;
    private final UserRegistry userRegistry;

    // 按用户名记录失败次数与锁定截止时间（内存态，重启即清零）
    private final Map<String, int[]> failCount = new ConcurrentHashMap<>();
    private final Map<String, Long> lockUntil = new ConcurrentHashMap<>();

    /** 登录 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        DcuConfiguration.Admin admin = config.getAdmin();
        Map<String, Object> m = new LinkedHashMap<>();

        // 锁定中：即使密码正确也拒绝（#15）
        Long until = lockUntil.get(username);
        if (until != null && until > System.currentTimeMillis()) {
            m.put("ok", false);
            m.put("message", "登录失败次数过多，请 " + (until - System.currentTimeMillis()) / 1000 + " 秒后再试");
            return m;
        }

        if (admin.getUsername().equals(username) && admin.getPassword().equals(password)) {
            // 登录成功：清失败计数，并重建 session 防 session fixation（#15）
            failCount.remove(username);
            lockUntil.remove(username);
            HttpSession old = request.getSession(false);
            if (old != null) {
                old.invalidate();
            }
            HttpSession fresh = request.getSession(true);
            fresh.setAttribute(SESSION_KEY, true);
            m.put("ok", true);
        } else {
            int[] counter = failCount.compute(username, (k, v) -> {
                if (v == null) {
                    return new int[]{1};
                }
                v[0]++;
                return v;
            });
            int fails = counter[0];
            if (fails >= MAX_FAILS) {
                lockUntil.put(username, System.currentTimeMillis() + LOCK_MS);
                failCount.remove(username);
                m.put("ok", false);
                m.put("message", "登录失败次数过多，已锁定 5 分钟");
            } else {
                m.put("ok", false);
                m.put("message", "用户名或密码错误");
            }
        }
        return m;
    }

    /** 登出 */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.removeAttribute(SESSION_KEY);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    /** 当前是否已登录 */
    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loggedIn", Boolean.TRUE.equals(session.getAttribute(SESSION_KEY)));
        return m;
    }

    /** 用户列表 */
    @GetMapping("/users")
    public Map<String, Object> users(HttpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!isAdmin(session)) {
            m.put("ok", false);
            m.put("message", "未登录");
            return m;
        }
        m.put("ok", true);
        m.put("data", userStore.list());
        return m;
    }

    /** 新增用户（apiKey 为空则自动生成） */
    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!isAdmin(session)) {
            m.put("ok", false);
            m.put("message", "未登录");
            return m;
        }
        try {
            Integer maxConcurrency = body.get("maxConcurrency") == null
                    ? null : Integer.valueOf(body.get("maxConcurrency").toString());
            long id = userStore.create(body.get("name"), body.get("apiKey"), maxConcurrency);
            userRegistry.refresh();
            m.put("ok", true);
            m.put("id", id);
        } catch (Exception e) {
            log.error("新增用户失败", e);
            m.put("ok", false);
            m.put("message", e.getMessage());
        }
        return m;
    }

    /** 更新用户（改名 / 启用禁用 / 重置 key） */
    @PutMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable long id,
                                          @RequestBody Map<String, Object> body,
                                          HttpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!isAdmin(session)) {
            m.put("ok", false);
            m.put("message", "未登录");
            return m;
        }
        try {
            String name = (String) body.get("name");
            Integer enabled = body.get("enabled") == null ? null : ((Number) body.get("enabled")).intValue();
            Integer maxConcurrency = body.get("maxConcurrency") == null
                    ? null : ((Number) body.get("maxConcurrency")).intValue();
            if (Boolean.TRUE.equals(body.get("resetKey"))) {
                // 重置 key 与改 name/enabled/max_concurrency 合并为单事务（#13），避免中间态
                String key = userStore.updateWithKey(id, name, userStore.generateKey(), enabled, maxConcurrency);
                m.put("ok", true);
                m.put("newKey", key);
            } else {
                userStore.update(id, name, null, enabled, maxConcurrency);
                m.put("ok", true);
            }
            userRegistry.refresh();
        } catch (Exception e) {
            log.error("更新用户失败 id={}", id, e);
            m.put("ok", false);
            m.put("message", e.getMessage());
        }
        return m;
    }

    /** 删除用户 */
    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable long id, HttpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!isAdmin(session)) {
            m.put("ok", false);
            m.put("message", "未登录");
            return m;
        }
        try {
            userStore.delete(id);
            userRegistry.refresh();
            m.put("ok", true);
        } catch (Exception e) {
            log.error("删除用户失败 id={}", id, e);
            m.put("ok", false);
            m.put("message", e.getMessage());
        }
        return m;
    }

    private boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute(SESSION_KEY));
    }
}
