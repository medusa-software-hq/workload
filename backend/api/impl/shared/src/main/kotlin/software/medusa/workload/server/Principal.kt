package software.medusa.workload.server

/**
 * A verified caller on the admin plane. Two kinds share one plane (GCP itself models this as
 * `user:` vs `serviceAccount:` members on the same resources — not separate endpoints):
 *
 * - [Human]: a person signed in through Google, `hd` = the Workspace domain.
 * - [Service]: a service account, its Google-signed ID token minted for the API's own audience and
 *   its email on a Terraform-managed allowlist.
 *
 * Authorization is coarse in M5: any verified principal is a full admin. Per-principal RPC
 * restriction would slot in at the call sites that read [kind] (see FleetServiceImpl). This type is
 * deliberately not admin-specific — M7's node principal verifies GCE-node SA ID tokens the same
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

  data class Service(override val email: String) : Principal {
    override val kind: String
      get() = "service"
  }
}
