-- M6-B2: record when a worker was revoked. Lets `admin workers list` hide revoked workers by
-- default and show a REVOKED AT column behind `--include-revoked`. Null for never-revoked workers.
-- (The larger lifecycle-state cleanup from the M6 design — the registrations attempt-table and the
-- status-enum shrink — is deferred pending the M8 group-enrollment decision; this adds only the
-- revocation timestamp, which stands regardless of that outcome.)
ALTER TABLE workers ADD COLUMN revoked_at timestamptz;
