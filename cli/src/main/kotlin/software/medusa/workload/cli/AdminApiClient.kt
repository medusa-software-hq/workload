package software.medusa.workload.cli

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.medusa.workload.runtime.BrokerUnreachableException

private const val fleetService = "/medusa.workload.v1.FleetService"

private val apiJson = Json { ignoreUnknownKeys = true }

/** A profile summary as returned by FleetService.ListProfiles (proto3 JSON — camelCase). */
@Serializable
data class AdminProfile(
    val profileId: String = "",
    val displayName: String = "",
    val latestRevision: Int = 0,
    val archived: Boolean = false,
    val createdAt: String = "",
)

/**
 * A worker as returned by the worker RPCs. Enums arrive as their proto name (e.g.
 * WORKER_STATUS_ACTIVE), kept as a String and mapped for display in [AdminFormat].
 */
@Serializable
data class AdminWorker(
    val workerId: String = "",
    val name: String = "",
    val hostname: String = "",
    val os: String = "",
    val cliVersion: String = "",
    val status: String = "",
    val createdAt: String = "",
    val approvedAt: String = "",
    val approvedBy: String = "",
    val lastSeenAt: String = "",
    val grantedProfileIds: List<String> = emptyList(),
    val sourceIp: String = "",
    val revokedAt: String = "",
    val assignmentStatuses: List<AdminAssignmentStatus> = emptyList(),
)

/**
 * One profile's reconcile status on a worker (M7 automated rollout), as reported by
 * `workload-agent`. `state` arrives as its proto enum name (e.g. AGENT_ASSIGNMENT_STATE_CONVERGED),
 * mapped for display in [AdminFormat].
 */
@Serializable
data class AdminAssignmentStatus(
    val profileId: String = "",
    val runningDigest: String = "",
    val desiredDigest: String = "",
    val state: String = "",
    val since: String = "",
    val drainDeadline: String = "",
)

/**
 * A run as returned by FleetService.ListRuns (proto3 JSON — camelCase). `state`/`kind` arrive as
 * their proto enum name (e.g. RUN_STATE_RUNNING); [hasExitCode] disambiguates a real exit 0 from
 * the proto3 default 0 of a still-running run.
 */
@Serializable
data class AdminRun(
    val runId: String = "",
    val workerId: String = "",
    val workerName: String = "",
    val profileId: String = "",
    val revision: Int = 0,
    val kind: String = "",
    val state: String = "",
    val exitCode: Int = 0,
    val hasExitCode: Boolean = false,
    val startedAt: String = "",
    val lastHeartbeatAt: String = "",
    val endedAt: String = "",
    val imageDigest: String = "",
)

/** A full profile revision — the read side that the create/update spec mirrors. */
@Serializable
data class AdminProfileRevision(
    val profileId: String = "",
    val revision: Int = 0,
    val targetServiceAccount: String = "",
    val createdAt: String = "",
    val createdBy: String = "",
    val note: String = "",
    val verificationStatus: String = "",
    val envVars: Map<String, String> = emptyMap(),
    val secretEnvVars: Map<String, String> = emptyMap(),
    val dockerImage: String = "",
    val dockerImageDigest: String = "",
    val imageStatus: String = "",
    val drainDeadline: String = "",
)

@Serializable
internal data class ListProfilesResponse(val profiles: List<AdminProfile> = emptyList())

@Serializable internal data class ListWorkersResponse(val workers: List<AdminWorker> = emptyList())

@Serializable internal data class ListRunsResponse(val runs: List<AdminRun> = emptyList())

/**
 * A first-class (worker, profile) placement record as FleetService lists it (proto3 JSON —
 * camelCase). Distinct from a grant: see the Assignment RPCs' doc comment in fleet_service.proto.
 */
@Serializable
data class AdminAssignment(
    val assignmentId: String = "",
    val workerId: String = "",
    val profileId: String = "",
    val createdAt: String = "",
    val createdBy: String = "",
)

@Serializable
internal data class ListAssignmentsResponse(val assignments: List<AdminAssignment> = emptyList())

@Serializable private data class ListAssignmentsRequest(val workerId: String, val profileId: String)

@Serializable
internal data class AssignmentResponse(val assignment: AdminAssignment = AdminAssignment())

@Serializable
private data class CreateAssignmentRequest(val workerId: String, val profileId: String)

@Serializable private data class DeleteAssignmentRequest(val assignmentId: String)

@Serializable
private data class ListRunsRequest(
    val profileId: String,
    val workerId: String,
    val liveOnly: Boolean,
)

@Serializable internal data class WorkerResponse(val worker: AdminWorker = AdminWorker())

@Serializable
internal data class ListProfileRevisionsResponse(
    val revisions: List<AdminProfileRevision> = emptyList()
)

@Serializable
internal data class RevisionResponse(val revision: AdminProfileRevision = AdminProfileRevision())

@Serializable internal data class ProfileResponse(val profile: AdminProfile = AdminProfile())

@Serializable
internal data class MutateProfileResponse(
    val profile: AdminProfile = AdminProfile(),
    val revision: AdminProfileRevision = AdminProfileRevision(),
)

// The CLI never sends expected_docker_image_digest (the CAS token) — that's the console's
// preview-then-pin path. Omitting it means "resolve the tag fresh and pin it, no check", which the
// proto documents as the non-console-client behaviour.
@Serializable
private data class CreateProfileRequest(
    val profileId: String,
    val displayName: String,
    val targetServiceAccount: String,
    val note: String,
    val envVars: Map<String, String>,
    val secretEnvVars: Map<String, String>,
    val dockerImage: String,
    val drainDeadline: String,
)

@Serializable
private data class UpdateProfileRequest(
    val profileId: String,
    val targetServiceAccount: String,
    val note: String,
    val envVars: Map<String, String>,
    val secretEnvVars: Map<String, String>,
    val dockerImage: String,
    val drainDeadline: String,
)

/**
 * An outstanding enrollment token as FleetService lists it (proto3 JSON — camelCase). Never carries
 * the plaintext token or its hash — only the metadata to list, identify, and revoke it.
 */
@Serializable
data class AdminEnrollmentToken(
    val enrollmentTokenId: String = "",
    val note: String = "",
    val createdBy: String = "",
    val createdAt: String = "",
    val expiresAt: String = "",
    val requireApproval: Boolean = false,
)

@Serializable
private data class CreateEnrollmentTokenRequest(
    val note: String,
    val expiresInDays: Int,
    val requireApproval: Boolean,
)

/** Response of CreateEnrollmentToken: the plaintext `wle_` token (shown once) plus its metadata. */
@Serializable
data class CreateEnrollmentTokenResult(
    val token: String = "",
    val enrollmentToken: AdminEnrollmentToken = AdminEnrollmentToken(),
)

@Serializable
internal data class ListEnrollmentTokensResponse(
    val enrollmentTokens: List<AdminEnrollmentToken> = emptyList()
)

@Serializable private data class RevokeEnrollmentTokenRequest(val enrollmentTokenId: String)

@Serializable private data class WorkerIdRequest(val workerId: String)

@Serializable private data class ProfileIdRequest(val profileId: String)

@Serializable private data class GrantRequest(val workerId: String, val profileId: String)

class AdminApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * Talks to the admin FleetService over its unframed (Connect/JSON) endpoint — a plain HTTPS POST of
 * the request message as JSON, with the caller's Google ID token as a bearer credential. Same shape
 * as [WorkerApiClient], different plane.
 */
class AdminApiClient(
    private val baseUrl: String,
    private val idTokenProvider: () -> String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
  fun listProfiles(): List<AdminProfile> =
      apiJson.decodeFromString<ListProfilesResponse>(post("ListProfiles", "{}")).profiles

  fun listProfileRevisions(profileId: String): List<AdminProfileRevision> =
      apiJson
          .decodeFromString<ListProfileRevisionsResponse>(
              post("ListProfileRevisions", apiJson.encodeToString(ProfileIdRequest(profileId)))
          )
          .revisions

  fun verifyProfile(profileId: String): AdminProfileRevision =
      apiJson
          .decodeFromString<RevisionResponse>(
              post("VerifyProfile", apiJson.encodeToString(ProfileIdRequest(profileId)))
          )
          .revision

  fun archiveProfile(profileId: String): AdminProfile =
      apiJson
          .decodeFromString<ProfileResponse>(
              post("ArchiveProfile", apiJson.encodeToString(ProfileIdRequest(profileId)))
          )
          .profile

  fun createProfile(
      profileId: String,
      displayName: String,
      spec: ProfileRevisionSpec,
  ): AdminProfileRevision =
      apiJson
          .decodeFromString<MutateProfileResponse>(
              post(
                  "CreateProfile",
                  apiJson.encodeToString(
                      CreateProfileRequest(
                          profileId = profileId,
                          displayName = displayName,
                          targetServiceAccount = spec.targetServiceAccount,
                          note = spec.note,
                          envVars = spec.envVars,
                          secretEnvVars = spec.secretEnvVars,
                          dockerImage = spec.dockerImage,
                          drainDeadline = spec.drainDeadline,
                      )
                  ),
              )
          )
          .revision

  fun updateProfile(profileId: String, spec: ProfileRevisionSpec): AdminProfileRevision =
      apiJson
          .decodeFromString<MutateProfileResponse>(
              post(
                  "UpdateProfile",
                  apiJson.encodeToString(
                      UpdateProfileRequest(
                          profileId = profileId,
                          targetServiceAccount = spec.targetServiceAccount,
                          note = spec.note,
                          envVars = spec.envVars,
                          secretEnvVars = spec.secretEnvVars,
                          dockerImage = spec.dockerImage,
                          drainDeadline = spec.drainDeadline,
                      )
                  ),
              )
          )
          .revision

  fun listWorkers(): List<AdminWorker> =
      apiJson.decodeFromString<ListWorkersResponse>(post("ListWorkers", "{}")).workers

  /**
   * Lists runs (M6-B2). [profileId]/[workerId] narrow the result (null = no filter); [liveOnly]
   * returns only runs that haven't ended (effective state RUNNING or LOST). Newest first.
   */
  fun listRuns(
      profileId: String? = null,
      workerId: String? = null,
      liveOnly: Boolean = false,
  ): List<AdminRun> =
      apiJson
          .decodeFromString<ListRunsResponse>(
              post(
                  "ListRuns",
                  apiJson.encodeToString(
                      ListRunsRequest(profileId.orEmpty(), workerId.orEmpty(), liveOnly)
                  ),
              )
          )
          .runs

  fun approveWorker(workerId: String): AdminWorker = worker("ApproveWorker", workerId)

  fun rejectWorker(workerId: String): AdminWorker = worker("RejectWorker", workerId)

  fun revokeWorker(workerId: String): AdminWorker = worker("RevokeWorker", workerId)

  fun grantProfile(workerId: String, profileId: String) {
    post("GrantProfile", apiJson.encodeToString(GrantRequest(workerId, profileId)))
  }

  fun revokeProfileGrant(workerId: String, profileId: String) {
    post("RevokeProfileGrant", apiJson.encodeToString(GrantRequest(workerId, profileId)))
  }

  /**
   * Lists assignments (a first-class placement record, distinct from a grant).
   * [workerId]/[profileId] narrow the result (null = no filter on that dimension).
   */
  fun listAssignments(workerId: String? = null, profileId: String? = null): List<AdminAssignment> =
      apiJson
          .decodeFromString<ListAssignmentsResponse>(
              post(
                  "ListAssignments",
                  apiJson.encodeToString(
                      ListAssignmentsRequest(workerId.orEmpty(), profileId.orEmpty())
                  ),
              )
          )
          .assignments

  fun createAssignment(workerId: String, profileId: String): AdminAssignment =
      apiJson
          .decodeFromString<AssignmentResponse>(
              post(
                  "CreateAssignment",
                  apiJson.encodeToString(CreateAssignmentRequest(workerId, profileId)),
              )
          )
          .assignment

  fun deleteAssignment(assignmentId: String) {
    post("DeleteAssignment", apiJson.encodeToString(DeleteAssignmentRequest(assignmentId)))
  }

  /**
   * Mints a one-time `wle_` enrollment token. [expiresInDays] <= 0 lets the server apply its
   * default (7); the returned [CreateEnrollmentTokenResult.token] is shown once and never
   * retrievable again.
   */
  fun createEnrollmentToken(
      note: String,
      expiresInDays: Int,
      requireApproval: Boolean,
  ): CreateEnrollmentTokenResult =
      apiJson.decodeFromString(
          post(
              "CreateEnrollmentToken",
              apiJson.encodeToString(
                  CreateEnrollmentTokenRequest(note, expiresInDays, requireApproval)
              ),
          )
      )

  fun listEnrollmentTokens(): List<AdminEnrollmentToken> =
      apiJson
          .decodeFromString<ListEnrollmentTokensResponse>(post("ListEnrollmentTokens", "{}"))
          .enrollmentTokens

  fun revokeEnrollmentToken(enrollmentTokenId: String) {
    post(
        "RevokeEnrollmentToken",
        apiJson.encodeToString(RevokeEnrollmentTokenRequest(enrollmentTokenId)),
    )
  }

  private fun worker(method: String, workerId: String): AdminWorker =
      apiJson
          .decodeFromString<WorkerResponse>(
              post(method, apiJson.encodeToString(WorkerIdRequest(workerId)))
          )
          .worker

  private fun post(method: String, body: String): String {
    val request =
        HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}$fleetService/$method"))
            .header("Authorization", "Bearer ${idTokenProvider()}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    // A failure to even reach the API (bad URL, DNS, offline, a front door that's down) surfaces as
    // a transport error. Translate it to BrokerUnreachableException — the same clean,
    // no-stack-trace
    // treatment the worker client gives, handled by the top-level `main` catch — instead of letting
    // a raw IOException reach the operator as a stack trace. Parity with the worker plane matters
    // more now that an IAM-locked front door sits in the path.
    val response =
        try {
          httpClient.send(request, BodyHandlers.ofString())
        } catch (e: IOException) {
          throw BrokerUnreachableException(
              "${request.uri().scheme}://${request.uri().authority}",
              e,
          )
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw BrokerUnreachableException(
              "${request.uri().scheme}://${request.uri().authority}",
              e,
          )
        }
    val status = response.statusCode()
    if (status == 401 || status == 403) {
      throw AdminApiException(
          status,
          "The API rejected your identity (HTTP $status). You may not be a workload admin, or your " +
              "session lapsed — try 'workload admin login' again.",
      )
    }
    if (status !in 200..299) {
      throw AdminApiException(
          status,
          "Admin API error (HTTP $status): ${response.body().take(500)}",
      )
    }
    return response.body()
  }
}
