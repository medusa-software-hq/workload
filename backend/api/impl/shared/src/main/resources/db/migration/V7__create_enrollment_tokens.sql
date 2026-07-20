-- One-time enrollment tokens (M4 Path A). An admin mints a `wle_` token and hands it to a teammate,
-- who exchanges it for a worker credential at registration (M4-A3); redemption burns it (single use).
-- Only the SHA-256 hash is stored — the plaintext is shown once at creation and never persisted.
-- See plan/m4/design/00-enrollment-tokens.md § Enrollment tokens.
CREATE TABLE enrollment_tokens (
    enrollment_token_id uuid PRIMARY KEY,
    token_hash          bytea NOT NULL UNIQUE,
    note                text,
    created_by          text NOT NULL,
    created_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    require_approval    boolean NOT NULL DEFAULT FALSE,
    -- Redemption sets used_at/used_by_worker_id; revocation sets revoked_at. An outstanding token
    -- has all three NULL (and expires_at in the future); used/expired/revoked rows are audit history.
    used_at             timestamptz,
    used_by_worker_id   uuid,
    revoked_at          timestamptz
);
