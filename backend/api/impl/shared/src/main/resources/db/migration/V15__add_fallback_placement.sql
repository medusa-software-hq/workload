-- Fallback auto-placement (workload#122 part 2). See FleetModel.kt's Profile.fallbackEligible and
-- Worker.fallbackNode.
ALTER TABLE profiles ADD COLUMN fallback_eligible boolean NOT NULL DEFAULT FALSE;
ALTER TABLE workers ADD COLUMN fallback_node boolean NOT NULL DEFAULT FALSE;
