-- Recent changes (internal/activity): who changed what in the space, from
-- the app or through Claude, so the two of you can see it in the Us tab.
-- Only shared things are logged; each entry is a kind and its details,
-- which the app words in its language.
CREATE TABLE activity (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    space_id   BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    user_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    -- 'app' or 'claude'
    via        TEXT NOT NULL DEFAULT 'app',
    kind       TEXT NOT NULL,
    details    JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX activity_space_created_idx ON activity (space_id, created_at DESC);
