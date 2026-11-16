-- 締め申請通知 (internal/shift): the days of the month the hours are sent in,
-- marked on the shift calendar. A day past the month's end (31) means its
-- last day, so the default is the 15th and the last day.
ALTER TABLE shift_settings ADD COLUMN closing_days INT[] NOT NULL DEFAULT '{15,31}';
