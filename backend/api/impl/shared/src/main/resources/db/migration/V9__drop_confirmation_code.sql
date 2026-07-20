-- Drops the workers.confirmation_code column (M4-A5). Confirmation codes belonged to the retired v1
-- open-registration plane: the human approving a pending worker matched a 4-digit code out-of-band.
-- v2 enrollment-token registration replaces that trust step (a one-time wle_ token proves the
-- requester was invited), so nothing reads or writes the column anymore.
ALTER TABLE workers DROP COLUMN confirmation_code;
