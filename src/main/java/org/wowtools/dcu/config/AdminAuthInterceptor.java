package org.wowtools.dcu.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 管理员登录拦截器：受保护接口（请求明细 / 用户统计 / 用户管理数据）要求已登录。
 *
 * <p>登录态与 {@code AdminController} 一致，用 HttpSession 的 {@code dcu.admin} 标记。
 * 未登录访问受保护接口返回 401 JSON，而非 Spring 默认错误页。
 * 这是接口层的强制控制，前端隐藏入口只是体验，真正的边界在这里。
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    /** 与 AdminController.SESSION_KEY 保持一致 */
    public static final String SESSION_KEY = "dcu.admin";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        HttpSession session = request.getSession(false);
        boolean loggedIn = session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_KEY));
        if (loggedIn) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"ok\":false,\"message\":\"未登录\"}");
        return false;
    }
}
