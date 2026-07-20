ALTER TABLE profile_revisions
    ADD COLUMN verification_status text NOT NULL DEFAULT 'UNVERIFIED';
