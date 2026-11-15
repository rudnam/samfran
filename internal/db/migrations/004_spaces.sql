-- A space is what the two of you share: everything in the app belongs to a
-- space, not to one person (internal/space). Each account is in exactly one
-- space. It gets its own the first time it signs in, unless someone invited
-- its email address, in which case it joins theirs.
CREATE TABLE spaces (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE space_members (
    user_id      BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    space_id     BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    -- What the app calls this person, e.g. on ratings. Empty means the part
    -- of their email before the @.
    display_name TEXT NOT NULL DEFAULT '',
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX space_members_space_id_idx ON space_members (space_id);

-- An invitation lets that address create an account (even with sign-up
-- closed) and puts the account in the inviter's space.
CREATE TABLE space_invites (
    email      TEXT PRIMARY KEY,
    space_id   BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    invited_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX space_invites_space_id_idx ON space_invites (space_id);

-- When the last member leaves (their account is deleted), the space goes
-- too, with everything in it. While one member remains, it stays theirs.
CREATE FUNCTION delete_empty_space() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM spaces s WHERE s.id = OLD.space_id
        AND NOT EXISTS (SELECT 1 FROM space_members m WHERE m.space_id = s.id);
    RETURN NULL;
END $$;
CREATE TRIGGER space_members_delete_empty_space
    AFTER DELETE ON space_members FOR EACH ROW EXECUTE FUNCTION delete_empty_space();
