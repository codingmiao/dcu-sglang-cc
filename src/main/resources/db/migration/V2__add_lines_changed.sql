-- V2：给 request_stat 加"改动行数"列。
-- 口径见 LinesChangedCalculator：Write 取 content 行数、Edit 取 new_string 行数，
-- 一次请求里多个 Write/Edit 块累加。存量行该列为 NULL（SUM 时按 0 计）。
ALTER TABLE request_stat ADD COLUMN lines_changed INTEGER;
