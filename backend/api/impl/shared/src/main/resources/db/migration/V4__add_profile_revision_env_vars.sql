-- Stored as JSON-encoded text (app-layer serialized/deserialized via kotlinx.serialization),
-- not `jsonb`, to keep the SQLDelight schema mirror a plain `text` column like everything else
-- here — no querying inside these maps at the SQL level is needed.
ALTER TABLE profile_revisions
    ADD COLUMN env_vars text NOT NULL DEFAULT '{}',
    ADD COLUMN secret_env_vars text NOT NULL DEFAULT '{}';
