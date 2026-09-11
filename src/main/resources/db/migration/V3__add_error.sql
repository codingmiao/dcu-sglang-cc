-- V3：给 request_stat 加"失败原因"列。
-- 成功行为 NULL；失败行存归一化后的原因（客户端断开 / 上游错误 HTTP xxx: 错误体 / 内部异常），
-- 见 DcuService.describeError（截断 500 字符）。存量失败行该列为 NULL（前端显示"未知"）。
ALTER TABLE request_stat ADD COLUMN error TEXT;
