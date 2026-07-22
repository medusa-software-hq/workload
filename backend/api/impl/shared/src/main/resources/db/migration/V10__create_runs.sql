-- Runs (M6 Path B / B1): the "who is running what" noun that presence derives from. A run is
-- created when `workload run`/`exec` starts a workload (after claim), heartbeats on a server-set
-- cadence, and is ended with the workload's exit code. See plan/m6/design/01-runs-and-presence.md.
--
-- `state` stores only RUNNING/SUCCEEDED/FAILED. `lost` is never written — it's derived at read time
-- from a still-RUNNING run whose last_heartbeat_at has aged past the grace window (RunPresence.kt),
-- so a late heartbeat un-loses a run for free and no background job is needed.
--
-- profile_id/revision are nullable to leave room for M7's `agent` kind (a session with no profile);
-- for run/exec they're always set. image_digest is display-only (the pinned digest a run ran).
CREATE TABLE runs (
    run_id            uuid PRIMARY KEY,
    worker_id         uuid NOT NULL REFERENCES workers,
    profile_id        text REFERENCES profiles,
    revision          int,
    kind              text NOT NULL,
    state             text NOT NULL,
    exit_code         int,
    started_at        timestamptz NOT NULL,
    last_heartbeat_at timestamptz NOT NULL,
    ended_at          timestamptz,
    image_digest      text
);

-- The presence and per-worker/per-profile run queries all filter on these.
CREATE INDEX runs_worker_id_idx ON runs (worker_id);
CREATE INDEX runs_profile_id_idx ON runs (profile_id);
