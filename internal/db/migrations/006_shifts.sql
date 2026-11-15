-- Shifts (internal/shift): one person's work schedule, read from a photo
-- of the monthly sheet or typed in, and offered as a calendar feed.

-- One row per space: whose shifts they are, and how to read the sheet.
CREATE TABLE shift_settings (
    space_id      BIGINT PRIMARY KEY REFERENCES spaces (id) ON DELETE CASCADE,
    -- What the app calls the person, e.g. "Sam".
    person_name   TEXT NOT NULL DEFAULT '',
    -- Their name as printed on the sheet, to find their row.
    name_on_sheet TEXT NOT NULL DEFAULT '',
    -- Hours on site for each letter of a shift code, e.g. {"F": 8}.
    -- NULL means the defaults (internal/shift.DefaultLengths).
    lengths       JSONB,
    time_zone     TEXT NOT NULL DEFAULT 'Asia/Tokyo',
    -- The calendar feed's secret (GET /api/shifts/feed/{token}); NULL until
    -- someone turns the feed on.
    feed_token    TEXT UNIQUE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The code for each day, as on the sheet ("13F", "普", "有休"…). Times are
-- worked out when read, so a change to the lengths applies to every day.
CREATE TABLE shifts (
    space_id   BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    day        DATE NOT NULL,
    code       TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, day)
);
