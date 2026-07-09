CREATE TABLE workers (
    worker_id         uuid PRIMARY KEY,
    secret_hash       bytea NOT NULL,
    name              text  NOT NULL,
    hostname          text,
    os                text,
    cli_version       text,
    status            text  NOT NULL, -- pending | active | rejected | revoked
    confirmation_code text,           -- only while pending
    created_at        timestamptz NOT NULL,
    approved_at       timestamptz,
    approved_by       text,
    last_seen_at      timestamptz
);

CREATE TABLE profiles (
    profile_id      text PRIMARY KEY, -- e.g. 'my-profile-1'
    display_name    text,
    latest_revision int  NOT NULL,
    archived        boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL
);

CREATE TABLE profile_revisions (
    profile_id             text NOT NULL REFERENCES profiles,
    revision                int NOT NULL,
    target_service_account text NOT NULL, -- M1 payload
    created_at              timestamptz NOT NULL,
    created_by              text NOT NULL,
    note                    text, -- optional "why" for the change
    PRIMARY KEY (profile_id, revision)
);

CREATE TABLE worker_profile_grants (
    worker_id  uuid NOT NULL REFERENCES workers,
    profile_id text NOT NULL REFERENCES profiles,
    granted_at timestamptz NOT NULL,
    granted_by text NOT NULL,
    PRIMARY KEY (worker_id, profile_id)
);
