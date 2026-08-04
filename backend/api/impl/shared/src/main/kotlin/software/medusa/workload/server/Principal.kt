package software.medusa.workload.server

/**
 * A verified caller on the admin plane. Two kinds share one plane (GCP itself models this as
 * `user:` vs `serviceAccount:` members on the same resources — not separate endpoints):
 *
 * - [Human]: a person signed in through Google, `hd` = the Workspace domain.
 * - [Service]: a service account, its Google-signed ID token minted for the API's own audience and
 *   its email on a Terraform-managed allowlist.
 *
 * Authorization was coarse through M5 (any verified principal was a full admin). The
 * automated-rollout epic's CI-push phase (workload#126 Phase 2) needed a narrower grant — a CI
 * principal that can only bump the digest of specific profiles (e.g. `flow-worker`) — so
 * [Service.allowedProfileIds] carries that scope: `null` means unrestricted (the original M5
 * behavior, still how e.g. the console's own s2s smoke-test principal works), a non-null set means
 * "UpdateProfile only, and only for a profile_id in this set" — enforced at the call sites that read
 * it (see `requireProfileWriteAccess`/`requireUnrestrictedPrincipal` in FleetServiceImpl). This type
 * is deliberately not admin-specific — M7's node principal verifies GCE-node SA ID tokens the same
 * way.
 */
sealed interface Principal {
  val email: String

  /** Stable, lowercase discriminator for audit logs. */
  val kind: String

  data class Human(override val email: String) : Principal {
    override val kind: String
      get() = "human"
  }

  data class Service(
      override val email: String,
      // null = unrestricted (full admin, the original M5 behavior). A non-null set restricts this
      // principal to UpdateProfile calls against only the profile_ids it contains.
      val allowedProfileIds: Set<String>? = null,
  ) : Principal {
    override val kind: String
      get() = "service"
  }
}
