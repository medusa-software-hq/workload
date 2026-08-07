-- Per-profile agent reconcile status (M7 automated rollout), reported by workload-agent's own
-- `POST /worker/v2/status` each tick. See FleetModel.kt's Worker.assignmentStatuses. JSON-encoded
-- (a point-in-time report, wholesale-replaced on every call, not relationally normalized audit
-- history) — the same treatment profile_revisions.env_vars already gets for the same reason.
ALTER TABLE workers ADD COLUMN assignment_status_json text;
