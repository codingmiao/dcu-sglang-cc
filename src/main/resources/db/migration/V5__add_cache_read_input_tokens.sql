-- V5：给 request_stat 加"缓存命中 token"列。
-- 对应 sglang --enable-cache-report 开启后 Anthropic 响应里的 cache_read_input_tokens。
-- 注意：sglang >= 0.5.14 的 input_tokens 语义变为"未命中部分"（prompt - cached），
-- 总输入 = input_tokens + cache_read_input_tokens。
-- 存量行该列为 NULL（前端显示 0 / 不显示）。
ALTER TABLE request_stat ADD COLUMN cache_read_input_tokens INTEGER;
