-- Google Calendar sync (internal/gcal): each person who connects gets a
-- calendar of the shifts in their own Google account, created by samfran and
-- kept up to date as shifts are saved. Samfran may only touch calendars it
-- created (the calendar.app.created scope).
CREATE TABLE calendar_links (
    user_id        BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    space_id       BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    -- Google's refresh token, sealed with AES-256-GCM under SECRETS_KEY.
    refresh_sealed BYTEA NOT NULL,
    -- The calendar samfran made; '' until it exists.
    calendar_id    TEXT NOT NULL DEFAULT '',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    synced_at      TIMESTAMPTZ,
    -- Why the last sync failed, for the person to see; '' when it worked.
    last_error     TEXT NOT NULL DEFAULT ''
);
CREATE INDEX calendar_links_space_id_idx ON calendar_links (space_id);
