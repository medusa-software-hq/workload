package software.medusa.workload.server

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import software.medusa.workload.db.Enrollment_tokens
import software.medusa.workload.db.Profile_revisions
import software.medusa.workload.db.Profiles
import software.medusa.workload.db.Worker_profile_grants
import software.medusa.workload.db.Workers
import software.medusa.workload.db.WorkloadDatabase

private val envVarsJson = Json { ignoreUnknownKeys = true }

private fun Instant.toOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

private fun Map<String, String>.toJson(): String = envVarsJson.encodeToString(this)

private fun String.toEnvVarMap(): Map<String, String> = envVarsJson.decodeFromString(this)

private fun Workers.toDomain(): Worker =
    Worker(
        workerId = WorkerId(worker_id),
        secretHash = SecretHash(secret_hash),
        name = name,
        hostname = hostname,
        os = os,
        cliVersion = cli_version,
        status = WorkerStatus.valueOf(status),
        confirmationCode = confirmation_code,
        createdAt = created_at.toInstant(),
        approvedAt = approved_at?.toInstant(),
        approvedBy = approved_by,
        lastSeenAt = last_seen_at?.toInstant(),
        registeredVia = RegisteredVia.valueOf(registered_via),
        sourceIp = source_ip,
    )

private fun Profiles.toDomain(): Profile =
    Profile(
        profileId = ProfileId(profile_id),
        displayName = display_name,
        latestRevision = latest_revision,
        archived = archived,
        createdAt = created_at.toInstant(),
    )

private fun Profile_revisions.toDomain(): ProfileRevision =
    ProfileRevision(
        profileId = ProfileId(profile_id),
        revision = revision,
        targetServiceAccount = target_service_account,
        createdAt = created_at.toInstant(),
        createdBy = created_by,
        note = note,
        verificationStatus = VerificationStatus.valueOf(verification_status),
        envVars = env_vars.toEnvVarMap(),
        secretEnvVars = secret_env_vars.toEnvVarMap(),
        dockerImage = docker_image,
        dockerImageDigest = docker_image_digest,
        imageStatus = ImageStatus.valueOf(image_status),
    )

private fun Worker_profile_grants.toDomain(): Grant =
    Grant(
        workerId = WorkerId(worker_id),
        profileId = ProfileId(profile_id),
        grantedAt = granted_at.toInstant(),
        grantedBy = granted_by,
    )

private fun Enrollment_tokens.toDomain(): EnrollmentToken =
    EnrollmentToken(
        id = EnrollmentTokenId(enrollment_token_id),
        tokenHash = SecretHash(token_hash),
        note = note,
        createdBy = created_by,
        createdAt = created_at.toInstant(),
        expiresAt = expires_at.toInstant(),
        requireApproval = require_approval,
        usedAt = used_at?.toInstant(),
        usedByWorkerId = used_by_worker_id?.let { WorkerId(it) },
        revokedAt = revoked_at?.toInstant(),
    )

class PostgresFleetStore(
    private val database: WorkloadDatabase,
) : FleetStore {
  override suspend fun createWorker(worker: NewWorker): Worker =
      withContext(Dispatchers.IO) {
        val workerId = UUID.randomUUID()
        database.fleetQueries.insertWorker(
            worker_id = workerId,
            secret_hash = worker.secretHash.bytes,
            name = worker.name,
            hostname = worker.hostname,
            os = worker.os,
            cli_version = worker.cliVersion,
            status = WorkerStatus.PENDING.name,
            confirmation_code = worker.confirmationCode,
            created_at = Instant.now().toOffsetDateTime(),
            registered_via = RegisteredVia.V1.name,
            source_ip = null,
        )
        database.fleetQueries.selectWorkerById(workerId).executeAsOne().toDomain()
      }

  override suspend fun registerWorkerWithEnrollmentToken(
      tokenHash: SecretHash,
      now: Instant,
      secretHash: SecretHash,
      name: String,
      hostname: String?,
      os: String?,
      cliVersion: String?,
      sourceIp: String,
  ): Worker? =
      withContext(Dispatchers.IO) {
        database.fleetQueries.transactionWithResult {
          val workerId = UUID.randomUUID()
          val burnt =
              database.fleetQueries
                  .burnEnrollmentToken(
                      used_at = now.toOffsetDateTime(),
                      used_by_worker_id = workerId,
                      token_hash = tokenHash.bytes,
                      expires_at = now.toOffsetDateTime(),
                  )
                  .executeAsOneOrNull() ?: return@transactionWithResult null
          val status = if (burnt.require_approval) WorkerStatus.PENDING else WorkerStatus.ACTIVE
          database.fleetQueries.insertWorker(
              worker_id = workerId,
              secret_hash = secretHash.bytes,
              name = name,
              hostname = hostname,
              os = os,
              cli_version = cliVersion,
              status = status.name,
              confirmation_code = null,
              created_at = now.toOffsetDateTime(),
              registered_via = RegisteredVia.V2.name,
              source_ip = sourceIp,
          )
          database.fleetQueries.selectWorkerById(workerId).executeAsOne().toDomain()
        }
      }

  override suspend fun getWorker(workerId: WorkerId): Worker? =
      withContext(Dispatchers.IO) {
        database.fleetQueries
            .selectWorkerById(workerId.value)
            .executeAsOneOrNull()
            ?.toDomain()
            ?.let { applyPendingExpiry(it) }
      }

  override suspend fun listWorkers(): List<Worker> =
      withContext(Dispatchers.IO) {
        database.fleetQueries.selectAllWorkers().executeAsList().map {
          applyPendingExpiry(it.toDomain())
        }
      }

  override suspend fun approveWorker(workerId: WorkerId, approvedBy: String): Worker? =
      withContext(Dispatchers.IO) {
        database.fleetQueries.approveWorker(
            Instant.now().toOffsetDateTime(),
            approvedBy,
            workerId.value,
        )
        database.fleetQueries.selectWorkerById(workerId.value).executeAsOneOrNull()?.toDomain()
      }

  override suspend fun rejectWorker(workerId: WorkerId): Worker? =
      withContext(Dispatchers.IO) {
        database.fleetQueries.rejectWorker(workerId.value)
        database.fleetQueries.selectWorkerById(workerId.value).executeAsOneOrNull()?.toDomain()
      }

  override suspend fun revokeWorker(workerId: WorkerId): Worker? =
      withContext(Dispatchers.IO) {
        database.fleetQueries.revokeWorker(workerId.value)
        database.fleetQueries.selectWorkerById(workerId.value).executeAsOneOrNull()?.toDomain()
      }

  override suspend fun touchLastSeen(workerId: WorkerId) {
    withContext(Dispatchers.IO) {
      database.fleetQueries.updateLastSeen(Instant.now().toOffsetDateTime(), workerId.value)
    }
  }

  override suspend fun createProfile(
      profileId: ProfileId,
      displayName: String?,
      revision: NewProfileRevision,
  ): Profile =
      withContext(Dispatchers.IO) {
        database.fleetQueries.transaction {
          val now = Instant.now().toOffsetDateTime()
          database.fleetQueries.insertProfile(
              profile_id = profileId.value,
              display_name = displayName,
              latest_revision = 1,
              archived = false,
              created_at = now,
          )
          database.fleetQueries.insertProfileRevision(
              profile_id = profileId.value,
              revision = 1,
              target_service_account = revision.targetServiceAccount,
              created_at = now,
              created_by = revision.createdBy,
              note = revision.note,
              env_vars = revision.envVars.toJson(),
              secret_env_vars = revision.secretEnvVars.toJson(),
              docker_image = revision.dockerImage,
              docker_image_digest = null,
              image_status = initialImageStatus(revision.dockerImage).name,
          )
        }
        database.fleetQueries.selectProfileById(profileId.value).executeAsOne().toDomain()
      }

  override suspend fun appendProfileRevision(
      profileId: ProfileId,
      revision: NewProfileRevision,
  ): ProfileRevision =
      withContext(Dispatchers.IO) {
        database.fleetQueries.transaction {
          val currentProfile =
              database.fleetQueries.selectProfileById(profileId.value).executeAsOne()
          val nextRevisionNumber = currentProfile.latest_revision + 1
          database.fleetQueries.insertProfileRevision(
              profile_id = profileId.value,
              revision = nextRevisionNumber,
              target_service_account = revision.targetServiceAccount,
              created_at = Instant.now().toOffsetDateTime(),
              created_by = revision.createdBy,
              note = revision.note,
              env_vars = revision.envVars.toJson(),
              secret_env_vars = revision.secretEnvVars.toJson(),
              docker_image = revision.dockerImage,
              docker_image_digest = null,
              image_status = initialImageStatus(revision.dockerImage).name,
          )
          database.fleetQueries.updateProfileLatestRevision(nextRevisionNumber, profileId.value)
        }
        database.fleetQueries.selectProfileById(profileId.value).executeAsOne().let { profile ->
          database.fleetQueries
              .selectProfileRevisionByNumber(profileId.value, profile.latest_revision)
              .executeAsOne()
              .toDomain()
        }
      }

  override suspend fun archiveProfile(profileId: ProfileId): Profile? =
      withContext(Dispatchers.IO) {
        database.fleetQueries.archiveProfile(profileId.value)
        database.fleetQueries.selectProfileById(profileId.value).executeAsOneOrNull()?.toDomain()
      }

  override suspend fun getProfile(profileId: ProfileId): Profile? =
      withContext(Dispatchers.IO) {
        database.fleetQueries.selectProfileById(profileId.value).executeAsOneOrNull()?.toDomain()
      }

  override suspend fun listProfiles(): List<Profile> =
      withContext(Dispatchers.IO) {
        database.fleetQueries.selectAllProfiles().executeAsList().map { it.toDomain() }
      }

  override suspend fun listProfileRevisions(profileId: ProfileId): List<ProfileRevision> =
      withContext(Dispatchers.IO) {
        database.fleetQueries.selectProfileRevisions(profileId.value).executeAsList().map {
          it.toDomain()
        }
      }

  override suspend fun getLatestProfileRevision(profileId: ProfileId): ProfileRevision? =
      withContext(Dispatchers.IO) {
        val profile =
            database.fleetQueries.selectProfileById(profileId.value).executeAsOneOrNull()
                ?: return@withContext null
        database.fleetQueries
            .selectProfileRevisionByNumber(profileId.value, profile.latest_revision)
            .executeAsOneOrNull()
            ?.toDomain()
      }

  override suspend fun recordVerification(
      profileId: ProfileId,
      revision: Int,
      status: VerificationStatus,
  ): ProfileRevision? =
      withContext(Dispatchers.IO) {
        database.fleetQueries.updateProfileRevisionVerification(
            status.name,
            profileId.value,
            revision,
        )
        database.fleetQueries
            .selectProfileRevisionByNumber(profileId.value, revision)
            .executeAsOneOrNull()
            ?.toDomain()
      }

  override suspend fun recordImageDigest(
      profileId: ProfileId,
      revision: Int,
      digest: String?,
      status: ImageStatus,
  ): ProfileRevision? =
      withContext(Dispatchers.IO) {
        database.fleetQueries.updateProfileRevisionImageDigest(
            digest,
            status.name,
            profileId.value,
            revision,
        )
        database.fleetQueries
            .selectProfileRevisionByNumber(profileId.value, revision)
            .executeAsOneOrNull()
            ?.toDomain()
      }

  override suspend fun grant(workerId: WorkerId, profileId: ProfileId, grantedBy: String): Grant =
      withContext(Dispatchers.IO) {
        val grantedAt = Instant.now()
        database.fleetQueries.upsertGrant(
            worker_id = workerId.value,
            profile_id = profileId.value,
            granted_at = grantedAt.toOffsetDateTime(),
            granted_by = grantedBy,
        )
        Grant(workerId, profileId, grantedAt, grantedBy)
      }

  override suspend fun revoke(workerId: WorkerId, profileId: ProfileId) {
    withContext(Dispatchers.IO) {
      database.fleetQueries.deleteGrant(workerId.value, profileId.value)
    }
  }

  override suspend fun hasGrant(workerId: WorkerId, profileId: ProfileId): Boolean =
      withContext(Dispatchers.IO) {
        database.fleetQueries.selectGrant(workerId.value, profileId.value).executeAsOneOrNull() !=
            null
      }

  override suspend fun listGrantedProfileIds(workerId: WorkerId): List<ProfileId> =
      withContext(Dispatchers.IO) {
        database.fleetQueries.selectGrantsByWorker(workerId.value).executeAsList().map {
          ProfileId(it.profile_id)
        }
      }

  override suspend fun createEnrollmentToken(token: NewEnrollmentToken): EnrollmentToken =
      withContext(Dispatchers.IO) {
        val id = UUID.randomUUID()
        database.fleetQueries.insertEnrollmentToken(
            enrollment_token_id = id,
            token_hash = token.tokenHash.bytes,
            note = token.note,
            created_by = token.createdBy,
            created_at = Instant.now().toOffsetDateTime(),
            expires_at = token.expiresAt.toOffsetDateTime(),
            require_approval = token.requireApproval,
        )
        database.fleetQueries.selectEnrollmentTokenById(id).executeAsOne().toDomain()
      }

  override suspend fun listOutstandingEnrollmentTokens(now: Instant): List<EnrollmentToken> =
      withContext(Dispatchers.IO) {
        database.fleetQueries
            .selectOutstandingEnrollmentTokens(now.toOffsetDateTime())
            .executeAsList()
            .map { it.toDomain() }
      }

  override suspend fun revokeEnrollmentToken(
      id: EnrollmentTokenId,
      now: Instant,
  ): EnrollmentToken? =
      withContext(Dispatchers.IO) {
        database.fleetQueries
            .revokeEnrollmentToken(now.toOffsetDateTime(), id.value)
            .executeAsOneOrNull()
            ?.toDomain()
      }

  override suspend fun burnEnrollmentToken(
      tokenHash: SecretHash,
      workerId: WorkerId,
      now: Instant,
  ): EnrollmentToken? =
      withContext(Dispatchers.IO) {
        database.fleetQueries
            .burnEnrollmentToken(
                used_at = now.toOffsetDateTime(),
                used_by_worker_id = workerId.value,
                token_hash = tokenHash.bytes,
                expires_at = now.toOffsetDateTime(),
            )
            .executeAsOneOrNull()
            ?.toDomain()
      }
}
