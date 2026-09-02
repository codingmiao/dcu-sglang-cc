package org.wowtools.dcu.util;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintWriter;

/**
 * Servlet 输出工具。
 */
@Slf4j
public class ServletUtil {

    @FunctionalInterface
    public interface EventStreamSendWriter {
        void use(PrintWriter writer) throws Exception;
    }

    public static void stream(HttpServletResponse response, EventStreamSendWriter esw) throws Exception {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        try (PrintWriter writer = response.getWriter()) {
            esw.use(writer);
        }
    }

    public static void text(String msg, int status, HttpServletResponse response) throws Exception {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(status);
        try {
            response.getWriter().write(msg);
            response.getWriter().flush();
        } catch (Exception e) {
            log.error("Non-stream request error", e);
            // 响应已提交（常见于客户端中途断开）时不能再 sendError，否则会抛
            // IllegalStateException 掩盖原始异常（#11）
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error");
            }
        }
    }
}
