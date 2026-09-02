-- V1 baseline：当前生产库的表结构快照。
-- 全部 IF NOT EXISTS：对"已有表、无 schema_version"的存量生产库是 no-op，只补版本戳。
-- 注意：schema_version 表由 SchemaMigrator 自己建，不在此文件里。

CREATE TABLE IF NOT EXISTS request_stat (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    log_id        TEXT,
    user          TEXT,
    model         TEXT,
    stream        INTEGER,
    input_tokens  INTEGER,
    output_tokens INTEGER,
    cost          INTEGER,
    stop_reason   TEXT,
    success       INTEGER,
    ts            INTEGER
);
CREATE INDEX IF NOT EXISTS idx_stat_ts ON request_stat(ts);
CREATE INDEX IF NOT EXISTS idx_stat_user ON request_stat(user);
CREATE INDEX IF NOT EXISTS idx_stat_log ON request_stat(log_id);

CREATE TABLE IF NOT EXISTS user (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT UNIQUE NOT NULL,
    api_key    TEXT UNIQUE NOT NULL,
    enabled    INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER,
    updated_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_user_key ON user(api_key);
