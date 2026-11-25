-- Locked apps (internal/applock): each person can lock apps on their own
-- phone, and opening one asks the other person first. And the phones'
-- Firebase addresses (internal/push), so the asking reaches the other phone
-- at once.

-- A phone's Firebase Cloud Messaging registration token. A phone that
-- signs in as someone else takes its token with it.
CREATE TABLE push_tokens (
    token      TEXT PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX push_tokens_user_id_idx ON push_tokens (user_id);

-- An app someone has locked on their own phone, by its Android package.
CREATE TABLE app_locks (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    space_id   BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    package    TEXT NOT NULL,
    label      TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, package)
);
CREATE INDEX app_locks_space_id_idx ON app_locks (space_id);

-- Asking to open a locked app for a while ('open'), to unlock it for good
-- ('remove'), or opening it without asking ('override', allowed at once and
-- the other person told). The app's package and label are kept, so the
-- history still names an app that's no longer locked.
CREATE TABLE app_lock_requests (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    space_id    BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    lock_id     BIGINT REFERENCES app_locks (id) ON DELETE SET NULL,
    package     TEXT NOT NULL,
    label       TEXT NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('open', 'remove', 'override')),
    minutes     INT NOT NULL DEFAULT 0,
    message     TEXT NOT NULL DEFAULT '',
    status      TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'allowed', 'denied', 'cancelled')),
    reply       TEXT NOT NULL DEFAULT '',
    open_until  TIMESTAMPTZ,
    answered_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    answered_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX app_lock_requests_space_id_idx ON app_lock_requests (space_id, created_at DESC);
CREATE INDEX app_lock_requests_lock_id_idx ON app_lock_requests (lock_id);
CREATE INDEX app_lock_requests_user_id_idx ON app_lock_requests (user_id);

-- A phone with locked apps checks in every 15 minutes: whether it can still
-- cover them (usage access, display over other apps, the watcher running).
-- The other person is told when covering stops, when it starts again, when
-- the watcher was stopped between two check-ins, and when a phone hasn't
-- checked in for a day.
CREATE TABLE lock_checkins (
    user_id       BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    space_id      BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    usage_access  BOOLEAN NOT NULL,
    overlay       BOOLEAN NOT NULL,
    watching      BOOLEAN NOT NULL,
    -- Why it stopped, when the phone says (signed_out); '' otherwise.
    reason        TEXT NOT NULL DEFAULT '',
    -- When the watcher started, as the phone said last.
    watching_since TIMESTAMPTZ,
    checked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The other person was told it went quiet (no check-in for a day).
    quiet_told    BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX lock_checkins_space_id_idx ON lock_checkins (space_id);
