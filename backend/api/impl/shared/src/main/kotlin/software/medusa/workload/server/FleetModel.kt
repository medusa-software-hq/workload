package software.medusa.workload.server

import java.time.Instant
import java.util.UUID

@JvmInline
value class WorkerId(
    val value: UUID,
)

/** A human-chosen slug, e.g. `my-profile-1`. Stable for the profile's lifetime. */
@JvmInline
value class ProfileId(
    val value: String,
) {
  init {
    require(profileIdPattern.matches(value)) {
      "profileId must match $profileIdPattern, was '$value'"
    }
    require(!value.startsWith("-") && !value.endsWith("-")) {
      "profileId must not start or end with '-', was '$value'"
    }
  }

  companion object {
    val profileIdPattern = Regex("[a-z0-9-]{3,63}")
  }
}

enum class WorkerStatus {
  PENDING,
  ACTIVE,
  REJECTED,
  REVOKED,
}

/**
 * Which registration plane a worker came through: [V1] the retired open-registration plane (M1), or
 * [V2] the one-time enrollment-token exchange (M4, now the only way to register). Retained so
 * existing V1 rows keep an accurate provenance after the v1 plane was removed in M4-A5.
 */
enum class RegisteredVia {
  V1,
  V2,
}

/** A SHA-256 digest, compared in constant time — never the plaintext secret. */
class SecretHash(
    val bytes: ByteArray,
) {
  override fun equals(other: Any?): Boolean =
      other is SecretHash && bytes.contentEquals(other.bytes)

  override fun hashCode(): Int = bytes.contentHashCode()
}

data class Worker(
    val workerId: WorkerId,
    val secretHash: SecretHash,
    val name: String,
    val hostname: String?,
    val os: String?,
    val cliVersion: String?,
    val status: WorkerStatus,
    val createdAt: Instant,
    val approvedAt: Instant?,
    val approvedBy: String?,
    val lastSeenAt: Instant?,
    val registeredVia: RegisteredVia = RegisteredVia.V1,
    // The source IP the worker registered from — recorded for v2 (the defense-in-depth signal on a
    // require_approval pending row); null for v1 workers.
    val sourceIp: String? = null,
    // When the worker was revoked (M6-B2); null unless status is REVOKED.
    val revokedAt: Instant? = null,
    // The operator pause/serve switch (M7-05): a drain, not a revoke. While true, this worker's
    // workload-agent reconciles its assignment set down to empty; the worker stays ACTIVE and
    // keeps its grants.
    val paused: Boolean = false,
    val pausedAt: Instant? = null,
)

data class NewWorker(
    val secretHash: SecretHash,
    val name: String,
    val hostname: String?,
    val os: String?,
    val cliVersion: String?,
)

data class Profile(
    val profileId: ProfileId,
    val displayName: String?,
    val latestRevision: Int,
    val archived: Boolean,
    val createdAt: Instant,
)

/**
 * Whether the target SA's owning project has granted the broker's runtime SA
 * `roles/iam.serviceAccountTokenCreator` (checked with a dry-run mint), and — if the revision
 * references any — whether that SA can read the referenced secrets. Checked on
 * create/update/verify. See design docs 04-impersonation-opt-in.md and
 * m2/stories/path-a/a1-env-in-revisions.md.
 */
enum class VerificationStatus {
  UNVERIFIED,
  VERIFIED,
  BINDING_MISSING,
  SECRET_INACCESSIBLE,
}

/**
 * Outcome of resolving a revision's [ProfileRevision.dockerImage] tag to an immutable digest at
 * creation time, via an Artifact Registry manifest request impersonating the revision's target SA.
 * Parallels [VerificationStatus]:
 * - [NOT_APPLICABLE] — the revision has no image (a pure exec/env Path A profile).
 * - [RESOLVED] — a digest was pinned; the revision is image-claimable.
 * - [UNRESOLVABLE] — a permanent denial (no `artifactregistry.reader` binding, a typo, or a missing
 *   tag/repo); the revision is flagged and not claimable.
 * - [UNDETERMINED] — a transient failure (timeout, 5xx); also not claimable, but retried on the
 *   next create/update/verify rather than treated as a permanent verdict.
 */
enum class ImageStatus {
  NOT_APPLICABLE,
  RESOLVED,
  UNRESOLVABLE,
  UNDETERMINED,
}

data class ProfileRevision(
    val profileId: ProfileId,
    val revision: Int,
    val targetServiceAccount: String,
    val createdAt: Instant,
    val createdBy: String,
    val note: String?,
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
    val envVars: Map<String, String> = emptyMap(),
    val secretEnvVars: Map<String, String> = emptyMap(),
    val dockerImage: String? = null,
    val dockerImageDigest: String? = null,
    val imageStatus: ImageStatus = ImageStatus.NOT_APPLICABLE,
    // The payload-declared stop timeout ("D") a supervisor passes when SIGTERM-draining a claim of
    // this revision, e.g. "6h" for the Flow worker. Null means "the supervisor's own default".
    val drainDeadline: String? = null,
)

data class NewProfileRevision(
    val targetServiceAccount: String,
    val createdBy: String,
    val note: String? = null,
    val envVars: Map<String, String> = emptyMap(),
    val secretEnvVars: Map<String, String> = emptyMap(),
    val dockerImage: String? = null,
    val drainDeadline: String? = null,
)

/**
 * The [ImageStatus] a freshly-inserted revision carries before digest resolution runs: pending
 * ([ImageStatus.UNDETERMINED]) when it has an image, [ImageStatus.NOT_APPLICABLE] otherwise. The
 * service overwrites this via `recordImageDigest` once resolution completes.
 */
fun initialImageStatus(dockerImage: String?): ImageStatus =
    if (dockerImage == null) ImageStatus.NOT_APPLICABLE else ImageStatus.UNDETERMINED

data class Grant(
    val workerId: WorkerId,
    val profileId: ProfileId,
    val grantedAt: Instant,
    val grantedBy: String,
)

@JvmInline
value class AssignmentId(
    val value: UUID,
)

/**
 * A first-class, admin-managed (worker, profile) placement record — distinct from a [Grant]. A
 * grant is authorization ("worker may run profile"); an assignment is the placement decision
 * itself, with its own id, independently listable/creatable/deletable. The two are deliberately
 * separate primitives — creating one does not imply the other.
 */
data class Assignment(
    val id: AssignmentId,
    val workerId: WorkerId,
    val profileId: ProfileId,
    val createdAt: Instant,
    val createdBy: String,
)

data class NewAssignment(
    val workerId: WorkerId,
    val profileId: ProfileId,
    val createdBy: String,
)

@JvmInline
value class EnrollmentTokenId(
    val value: UUID,
)

/**
 * A one-time `wle_` enrollment token an admin mints and hands to a teammate, who exchanges it for a
 * worker credential (M4-A3). Only the SHA-256 [tokenHash] is persisted — the plaintext is shown
 * once at creation and never stored. A token is *outstanding* while [usedAt], [revokedAt], and
 * expiry are all clear; redeeming it sets [usedAt]/[usedByWorkerId], revoking it sets [revokedAt],
 * and either way it drops out of the outstanding list but survives as audit history.
 */
data class EnrollmentToken(
    val id: EnrollmentTokenId,
    val tokenHash: SecretHash,
    val note: String?,
    val createdBy: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val requireApproval: Boolean,
    val usedAt: Instant?,
    val usedByWorkerId: WorkerId?,
    val revokedAt: Instant?,
)

data class NewEnrollmentToken(
    val tokenHash: SecretHash,
    val note: String?,
    val createdBy: String,
    val expiresAt: Instant,
    val requireApproval: Boolean,
)

// Runs (M6-B1) — the "who is running what" noun. Presence is derived from live runs, never stored.

@JvmInline
value class RunId(
    val value: UUID,
)

/**
 * What kind of workload a run represents. `AGENT` (M7-05) is a long-lived `workload-agent` presence
 * session — no profile/revision, which is why [Run.profileId]/[Run.revision] are nullable —
 * heartbeated for as long as the daemon is up and ended on clean shutdown.
 */
enum class RunKind {
  RUN,
  EXEC,
  AGENT,
}

/**
 * A run's lifecycle state. [RUNNING], [SUCCEEDED], and [FAILED] are the only values ever *stored*;
 * [LOST] is derived at read time — a still-`RUNNING` run whose last heartbeat has aged past the
 * grace window (see [deriveRunState]). Nothing false is ever written, so a late heartbeat un-loses
 * a run for free, exactly like [applyPendingExpiry] for pending workers.
 */
enum class RunState {
  RUNNING,
  SUCCEEDED,
  FAILED,
  LOST,
}

data class Run(
    val runId: RunId,
    val workerId: WorkerId,
    // Nullable to accommodate M7's `AGENT` kind; always set for RUN/EXEC.
    val profileId: ProfileId?,
    val revision: Int?,
    val kind: RunKind,
    // The *effective* state as returned by the store: read paths apply [deriveRunState], so this
    // may
    // be [RunState.LOST] even though only RUNNING/SUCCEEDED/FAILED are persisted.
    val state: RunState,
    val exitCode: Int?,
    val startedAt: Instant,
    val lastHeartbeatAt: Instant,
    val endedAt: Instant?,
    val imageDigest: String?,
)

data class NewRun(
    val workerId: WorkerId,
    val profileId: ProfileId?,
    val revision: Int?,
    val kind: RunKind,
    val imageDigest: String? = null,
)

/** Filters for [FleetStore.listRuns]; every field is an independent, ANDed narrowing. */
data class RunFilter(
    val workerId: WorkerId? = null,
    val profileId: ProfileId? = null,
    // Only runs that haven't ended yet (effective state RUNNING or LOST). The presence queries and
    // the console's live view build on this; the default runs list passes false to include history.
    val liveOnly: Boolean = false,
)
