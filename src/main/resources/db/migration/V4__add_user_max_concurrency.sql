-- V4：给 user 表加"每用户最大并发"列。
-- 用户级并发门的上限：每个用户同时最多跑 max_concurrency 个 /v1/messages 请求，
-- 超限立即拒绝（429）。存量行取默认值 2。
ALTER TABLE user ADD COLUMN max_concurrency INTEGER NOT NULL DEFAULT 2;
