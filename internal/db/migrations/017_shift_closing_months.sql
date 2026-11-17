-- 締め申請通知 for one month (internal/shift): the days that month's hours
-- are sent in, when they differ from the usual ones (shift_settings.
-- closing_days). Days are dates in the month (1 to its last day); an empty
-- list means none that month. No row: the usual days.
CREATE TABLE shift_closing_months (
    space_id   BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    month      DATE NOT NULL, -- the month's first day
    days       INT[] NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, month)
);
