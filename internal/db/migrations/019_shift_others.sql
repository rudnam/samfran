-- Who else is there during a shift (internal/shift, others.go): the others on
-- other shifts whose hours overlap it, as [{"name", "code", "day_offset"}].
-- The default keeps the previous release's inserts working.
ALTER TABLE shifts ADD COLUMN others JSONB NOT NULL DEFAULT '[]';
