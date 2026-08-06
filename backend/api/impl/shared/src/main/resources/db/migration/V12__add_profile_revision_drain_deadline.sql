-- Phase 2 of the automated-rollout epic (workload#126): the payload-declared stop timeout ("D")
-- a supervisor passes when SIGTERM-draining a claim of this revision, e.g. "6h" for the Flow
-- worker. Null means "the supervisor's own default" — existing revisions are unaffected.
ALTER TABLE profile_revisions
    ADD COLUMN drain_deadline text;
