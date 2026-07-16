-- Optional container image for a revision (Path A profiles stay pure exec/env and leave these
-- null). `docker_image` is the caller-supplied tag ref; `docker_image_digest` is resolved by the
-- backend at revision creation via an Artifact Registry manifest request (impersonating the
-- revision's target SA) and is null until/unless resolution succeeds. `image_status` records the
-- resolution verdict, mirroring `verification_status`: NOT_APPLICABLE when there's no image,
-- RESOLVED once a digest is pinned, UNRESOLVABLE on a permanent denial (missing binding, typo,
-- missing tag), UNDETERMINED on a transient failure. Anything other than RESOLVED (for an image
-- profile) blocks claims, same as an unverified revision.
ALTER TABLE profile_revisions
    ADD COLUMN docker_image text,
    ADD COLUMN docker_image_digest text,
    ADD COLUMN image_status text NOT NULL DEFAULT 'NOT_APPLICABLE';
