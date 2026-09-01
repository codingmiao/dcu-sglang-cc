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

    /** 输入 token */
    private int inputTokens;

    /** 输出 token */
    private int outputTokens;

    /** 耗时（ms） */
    private long cost;

    /** 停止原因 */
    private String stopReason;

    /** 是否成功 */
    private boolean success;

    /** 记录时间（epoch millis） */
    private long ts;
}
