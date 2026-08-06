-- The operator pause/serve switch (M7-05): a drain, not a revoke. See FleetModel.kt's Worker.paused.
ALTER TABLE workers ADD COLUMN paused boolean NOT NULL DEFAULT FALSE;
ALTER TABLE workers ADD COLUMN paused_at TIMESTAMPTZ;
