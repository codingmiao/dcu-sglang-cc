package org.wowtools.dcu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 服务核心配置。
 *
 * <pre>
 * dcu:
 *   backend:
 *     inner-name: chat_min
 *     base-url: http://sk-ai:19800
 *     api-key: sk-ai
 *   fix:
 *     system-role: true
 *     mismatched-delta: true
 *   users:
 *     - name: alice
 *       api-key: key-alice
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "dcu")
@Data
public class DcuConfiguration {

    /** 连接超时（秒） */
    private int connectTimeout = 30;

    /** 读取超时（秒） */
    private int readTimeout = 600;

    /** 写入超时（秒） */
    private int writeTimeout = 600;

    /**
     * /v1/messages 最大并发数（同时转发到 sglang 的请求上限）。
     * 超过后进入排队；排队也满则返回系统繁忙。
     */
    private int maxConcurrency = 16;

    /** 排队长度上限（等待并发许可的请求数） */
    private int queueSize = 128;

    /** 排队等待并发许可的超时（秒），超时返回系统繁忙 */
    private int queueWaitTimeout = 60;

    /** 后端 sglang 配置 */
    private Backend backend;

    /** 修复开关 */
    private Fix fix = new Fix();

    /** 统计 / 明细记录配置 */
    private Stats stats = new Stats();

    /** 管理员（登录管理页用） */
    private Admin admin = new Admin();

    /** 可用用户列表（首次启动 seed 到 user 表） */
    private List<User> users;

    @Data
    public static class Backend {
        /** 对内模型名，转发给 sglang 时使用的 model */
        private String innerName;

        /** sglang 基础地址，如 http://sk-ai:19800 */
        private String baseUrl;

        /** 转发给 sglang 的 x-api-key */
        private String apiKey;
    }

    @Data
    public static class Fix {
        /** 是否把非 assistant 的 role 改成 user（sglang 不支持 system/tool 等角色） */
        private boolean systemRole = false;

        /**
         * 是否丢弃与所在 content block 类型不匹配的 content_block_delta。
         * sglang 输出多个 tool call 时，会把 tool 之间模型吐出的分隔文本（如 "\n"）
         * 以 text_delta 形式混进 tool_use 块的事件流，客户端会报
         * "Content block is not a text block"。
         */
        private boolean mismatchedDelta = false;
    }

    @Data
    public static class Stats {
        /** 明细 jsonl 全量日志目录 */
        private String dataDir = "data";

        /** 单个 jsonl 文件大小阈值（字节），超过则滚动并压缩，默认 20MB */
        private long maxFileSize = 20L * 1024 * 1024;

        /** SQLite 数据库文件路径 */
        private String sqlitePath = "data/stats.db";
    }

    @Data
    public static class User {
        /** 用户名 */
        private String name;

        /** 该用户的 apiKey，客户端必须携带正确的 apiKey 才能访问 */
        private String apiKey;
    }

    @Data
    public static class Admin {
        /** 管理员用户名 */
        private String username = "admin";

        /** 管理员密码 */
        private String password = "admin";
    }
}
