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

data class ProfileRevision(
    val profileId: ProfileId,
    val revision: Int,
    val targetServiceAccount: String,
    val createdAt: Instant,
    val createdBy: String,
    val note: String?,
)

data class NewProfileRevision(
    val targetServiceAccount: String,
    val createdBy: String,
    val note: String? = null,
)

data class Grant(
    val workerId: WorkerId,
    val profileId: ProfileId,
    val grantedAt: Instant,
    val grantedBy: String,
)
