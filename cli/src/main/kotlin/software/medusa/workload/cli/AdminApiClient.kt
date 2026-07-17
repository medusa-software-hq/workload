package software.medusa.workload.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val confirmationCode: String = "",
    val createdAt: String = "",
    val approvedAt: String = "",
    val approvedBy: String = "",
    val lastSeenAt: String = "",
    val grantedProfileIds: List<String> = emptyList(),
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
)

@Serializable
internal data class ListProfilesResponse(val profiles: List<AdminProfile> = emptyList())

@Serializable internal data class ListWorkersResponse(val workers: List<AdminWorker> = emptyList())

@Serializable internal data class WorkerResponse(val worker: AdminWorker = AdminWorker())

@Serializable
internal data class ListProfileRevisionsResponse(
    val revisions: List<AdminProfileRevision> = emptyList()
)

@Serializable
internal data class RevisionResponse(val revision: AdminProfileRevision = AdminProfileRevision())

@Serializable internal data class ProfileResponse(val profile: AdminProfile = AdminProfile())

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

  fun listWorkers(): List<AdminWorker> =
      apiJson.decodeFromString<ListWorkersResponse>(post("ListWorkers", "{}")).workers

  fun approveWorker(workerId: String): AdminWorker = worker("ApproveWorker", workerId)

  fun rejectWorker(workerId: String): AdminWorker = worker("RejectWorker", workerId)

  fun revokeWorker(workerId: String): AdminWorker = worker("RevokeWorker", workerId)

  fun grantProfile(workerId: String, profileId: String) {
    post("GrantProfile", apiJson.encodeToString(GrantRequest(workerId, profileId)))
  }

  fun revokeProfileGrant(workerId: String, profileId: String) {
    post("RevokeProfileGrant", apiJson.encodeToString(GrantRequest(workerId, profileId)))
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
    val response = httpClient.send(request, BodyHandlers.ofString())
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
