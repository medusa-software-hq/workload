package software.medusa.workload.server

/**
 * A verified caller on the admin plane. Two kinds share one plane (GCP itself models this as
 * `user:` vs `serviceAccount:` members on the same resources — not separate endpoints):
 *
 * - [Human]: a person signed in through Google, `hd` = the Workspace domain.
 * - [Service]: a service account, its Google-signed ID token minted for the API's own audience and
 *   its email on a Terraform-managed allowlist.
 *
 * Authorization was coarse through M5 (any verified principal was a full admin). Phase 2 of the
 * automated-rollout epic (workload#126) adds one restriction, carried on [Service.profileScope]: a
 * CI principal that only ever needs to push a digest (e.g. Flow's release automation) can be
 * allow-listed with a non-empty scope, which [FleetServiceImpl] confines to profile
 * create/update/read RPCs against exactly those profile ids — every other admin RPC rejects it
 * outright. An empty scope is the pre-existing unrestricted s2s admin (the `workload-ci-admin` SA)
 * and is unaffected. This type is deliberately not admin-specific — M7's node principal verifies
 * GCE-node SA ID tokens the same way (and never carries a profile scope).
 */
sealed interface Principal {
  val email: String

  /** Stable, lowercase discriminator for audit logs. */
  val kind: String

  data class Human(override val email: String) : Principal {
    override val kind: String
      get() = "human"
  }

  /**
   * [profileScope] is empty for the unrestricted s2s admin principal (full FleetService access,
   * M5-05). Non-empty restricts the principal to exactly those profile ids on the RPCs that accept
   * a profile id — see [FleetServiceImpl]'s `requireProfileScope`/`requireUnscopedPrincipal`.
   */
  data class Service(override val email: String, val profileScope: Set<String> = emptySet()) :
      Principal {
    override val kind: String
      get() = "service"
  }
}
