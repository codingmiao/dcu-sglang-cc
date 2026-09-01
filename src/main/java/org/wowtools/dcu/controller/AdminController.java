package org.wowtools.dcu.controller;

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

    private final DcuConfiguration config;
    private final UserStore userStore;
    private final UserRegistry userRegistry;

    /** 登录 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpSession session) {
        String username = body.get("username");
        String password = body.get("password");
        DcuConfiguration.Admin admin = config.getAdmin();
        Map<String, Object> m = new LinkedHashMap<>();
        if (admin.getUsername().equals(username) && admin.getPassword().equals(password)) {
            session.setAttribute(SESSION_KEY, true);
            m.put("ok", true);
        } else {
            m.put("ok", false);
            m.put("message", "用户名或密码错误");
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
            long id = userStore.create(body.get("name"), body.get("apiKey"));
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
            if (Boolean.TRUE.equals(body.get("resetKey"))) {
                String key = userStore.resetKey(id);
                userStore.update(id, name, null, enabled);
                m.put("ok", true);
                m.put("newKey", key);
            } else {
                userStore.update(id, name, null, enabled);
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
