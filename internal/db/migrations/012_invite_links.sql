-- Invite links (internal/space/links.go): a member makes a link, the other
-- person opens it and signs up, and joins the space. Single use, seven days.
-- Only a SHA-256 hash of the link's secret is stored.
CREATE TABLE space_invite_links (
    token_hash BYTEA PRIMARY KEY,
    space_id   BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    created_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    used_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX space_invite_links_space_id_idx ON space_invite_links (space_id);
