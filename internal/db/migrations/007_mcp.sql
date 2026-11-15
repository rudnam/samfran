-- Claude as a connector (internal/mcp): Claude signs in with OAuth 2.1 and
-- PKCE, the person approves it on the Connect page, and Claude then uses
-- samfran's tools with an access token. As in RudWallet. Only SHA-256
-- hashes of tokens and codes are stored.

-- Clients that registered themselves (RFC 7591). They are public clients:
-- no secret, so PKCE is what proves a code was asked for by its client.
CREATE TABLE mcp_clients (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_id     TEXT NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    redirect_uris TEXT[] NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Approvals waiting to be exchanged for tokens: single use, a few minutes.
CREATE TABLE mcp_auth_codes (
    code_hash      BYTEA PRIMARY KEY,
    client_id      TEXT NOT NULL REFERENCES mcp_clients (client_id) ON DELETE CASCADE,
    user_id        BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    redirect_uri   TEXT NOT NULL,
    code_challenge TEXT NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL
);

-- One row per connection. A token from sign-in expires and is renewed with
-- its refresh token, which changes each time.
CREATE TABLE mcp_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash   BYTEA NOT NULL UNIQUE,
    user_id      BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT NOT NULL DEFAULT '',
    client_id    TEXT REFERENCES mcp_clients (client_id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ,
    refresh_hash BYTEA UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX mcp_tokens_user_id_idx ON mcp_tokens (user_id);
