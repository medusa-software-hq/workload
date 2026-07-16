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
    val confirmationCode: String?,
    val createdAt: Instant,
    val approvedAt: Instant?,
    val approvedBy: String?,
    val lastSeenAt: Instant?,
)

data class NewWorker(
    val secretHash: SecretHash,
    val name: String,
    val hostname: String?,
    val os: String?,
    val cliVersion: String?,
    val confirmationCode: String,
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
)

data class NewProfileRevision(
    val targetServiceAccount: String,
    val createdBy: String,
    val note: String? = null,
    val envVars: Map<String, String> = emptyMap(),
    val secretEnvVars: Map<String, String> = emptyMap(),
    val dockerImage: String? = null,
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
