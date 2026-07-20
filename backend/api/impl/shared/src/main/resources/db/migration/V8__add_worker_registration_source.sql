-- Records how each worker registered (M4-A3). `registered_via` distinguishes M1 open registration
-- (v1) from M4 enrollment-token exchange (V2) — the console shows it and the A5 v1-retirement gate
-- reads it. Existing workers predate v2, so they default to V1. `source_ip` is the address a worker
-- registered from, captured for v2 (the defense-in-depth signal on a require_approval pending row);
-- null for v1.
ALTER TABLE workers ADD COLUMN registered_via text NOT NULL DEFAULT 'V1';
ALTER TABLE workers ADD COLUMN source_ip text;
