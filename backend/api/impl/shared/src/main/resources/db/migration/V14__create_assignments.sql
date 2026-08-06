-- First-class assignments: a persisted (worker, profile) placement record, distinct from
-- worker_profile_grants (authorization). See Assignment's doc comment in fleet_service.proto.
CREATE TABLE assignments (
    assignment_id uuid PRIMARY KEY,
    worker_id     uuid NOT NULL REFERENCES workers,
    profile_id    text NOT NULL REFERENCES profiles,
    created_at    timestamptz NOT NULL,
    created_by    text NOT NULL
);
