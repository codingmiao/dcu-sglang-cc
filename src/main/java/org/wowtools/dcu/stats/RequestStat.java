package org.wowtools.dcu.stats;

import lombok.Data;

/**
 * 一次请求的统计明细（落 SQLite 的轻量字段）。
 * 全量请求/响应体另写 jsonl，这里只存可聚合的统计字段。
 */
@Data
public class RequestStat {

    /** 请求 id（logId） */
    private String logId;

    /** 用户名（由 apiKey 反查） */
    private String user;

    /** 模型名 */
    private String model;

    /** 是否流式 */
    private boolean stream;

    /** 输入 token（sglang >= 0.5.14 语义为"未命中部分"，总输入 = 本字段 + cacheReadInputTokens） */
    private int inputTokens;

    /** 缓存命中 token（sglang --enable-cache-report 开启后由 cache_read_input_tokens 上报） */
    private int cacheReadInputTokens;

    /** 输出 token */
    private int outputTokens;

    /** 改动行数（Write/Edit 工具，见 {@link LinesChangedCalculator}） */
    private int linesChanged;

    /** 耗时（ms） */
    private long cost;

    /** 停止原因 */
    private String stopReason;

    /** 是否成功 */
    private boolean success;

    /** 失败原因（成功时为 null）：上游错误带状态码与错误体、客户端断开、内部异常等 */
    private String error;

    /** 记录时间（epoch millis） */
    private long ts;
}
