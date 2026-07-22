package software.medusa.workload.cli

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RegisterWorkerResponse(
    val workerId: String,
    val workerSecret: String,
)

@Serializable
data class SelfStatusResponse(
    val status: String,
    val name: String,
    val grantedProfileIds: List<String> = emptyList(),
)

@Serializable
data class TokenClaimResponse(
    val accessToken: String,
    val expiresAt: String,
    val serviceAccount: String,
    val profileId: String,
    val revision: Int,
)

/** The revision's container image: the tag ref as typed, plus the digest it resolved to (M3-04). */
@Serializable data class ClaimImage(val ref: String, val digest: String)

@Serializable
data class WorkerClaimResponse(
    val accessToken: String,
    val expiresAt: String,
    val serviceAccount: String,
    val profileId: String,
    val revision: Int,
    val envVars: Map<String, String> = emptyMap(),
    val secretEnvVars: Map<String, String> = emptyMap(),
    /**
     * Null for a pure exec/env profile — `workload worker run` requires it, `workload worker exec`
     * doesn't.
     */
    val image: ClaimImage? = null,
)

@Serializable
data class WorkerIdTokenClaimResponse(
    val idToken: String,
    val expiresAt: String,
    val serviceAccount: String,
    val profileId: String,
    val revision: Int,
    val audience: String,
)

/** `{runId, heartbeatIntervalSeconds}` — the broker-minted run id and the cadence to heartbeat on. */
@Serializable
data class CreateRunResponse(
    val runId: String,
    val heartbeatIntervalSeconds: Long,
)

@Serializable private data class RegisterWorkerRequest(val name: String, val hostname: String?)

@Serializable
private data class CreateRunRequest(
    val profileId: String?,
    val revision: Int?,
    val kind: String,
    val imageDigest: String?,
)

@Serializable private data class EndRunRequest(val exitCode: Int?)

@Serializable private data class TokenClaimRequest(val profileId: String)

@Serializable
private data class IdTokenClaimRequest(
    val profileId: String,
    val audience: String,
    val includeEmail: Boolean,
)

@Serializable private data class WorkerErrorResponse(val error: String)

/** [errorCode] is the broker's machine-readable `error` field, e.g. `"not_granted"`. */
class WorkerApiException(val statusCode: Int, val errorCode: String) :
    Exception("Request failed with status $statusCode: $errorCode")

/**
 * The broker couldn't be reached at all — DNS didn't resolve, the connection was refused, or it
 * timed out. Extends [IOException] so the registration poll's transient-retry loop still treats a
 * momentary blip as retryable, while the initial call surfaces it as a clean message (no stack
 * trace) — see the top-level handler in `main`.
 */
class BrokerUnreachableException(val brokerHost: String, cause: Throwable) :
    IOException(
        "Couldn't reach the broker at $brokerHost (${cause.message ?: cause::class.simpleName}). " +
            "Check the URL is the broker's base — e.g. https://api-xxxx.a.run.app, with no " +
            "/worker/... path — and your network connection.",
        cause,
    )

private val json = Json { ignoreUnknownKeys = true }
private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

/**
 * Sends [request], translating a failure to even reach the host into [BrokerUnreachableException].
 */
private fun send(request: HttpRequest): HttpResponse<String> =
    try {
      httpClient.send(request, BodyHandlers.ofString())
    } catch (e: IOException) {
      throw BrokerUnreachableException("${request.uri().scheme}://${request.uri().authority}", e)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw BrokerUnreachableException("${request.uri().scheme}://${request.uri().authority}", e)
    }

/**
 * Calls `POST <brokerBaseUrl>/worker/v2/registrations`, exchanging a one-time `wle_` enrollment
 * token for a fresh worker credential (M4-A3). The token rides as the Bearer credential; the broker
 * burns it and returns `{workerId, workerSecret}` (the secret is a `wlw_` token). A rejected,
 * expired, burnt, or malformed token — or a wrong URL — comes back as a bare 404.
 */
fun registerWorker(
    brokerBaseUrl: String,
    name: String,
    enrollmentToken: String,
): RegisterWorkerResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v2/registrations"))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer $enrollmentToken")
          .timeout(Duration.ofSeconds(10))
          .POST(
              HttpRequest.BodyPublishers.ofString(
                  json.encodeToString(
                      RegisterWorkerRequest(name = name, hostname = localHostname())
                  )
              )
          )
          .build()

  val response = send(request)
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

/** Calls `GET <brokerBaseUrl>/worker/v2/registrations/self`. */
fun fetchSelfStatus(
    brokerBaseUrl: String,
    workerId: String,
    workerSecret: String,
): SelfStatusResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v2/registrations/self"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()

  val response = send(request)
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

/** Calls `POST <brokerBaseUrl>/worker/v2/token`. */
fun claimToken(
    brokerBaseUrl: String,
    workerId: String,
    workerSecret: String,
    profileId: String,
): TokenClaimResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v2/token"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(10))
          .POST(
              HttpRequest.BodyPublishers.ofString(json.encodeToString(TokenClaimRequest(profileId)))
          )
          .build()

  val response = send(request)
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

/** Calls `POST <brokerBaseUrl>/worker/v2/id-token`: an audience-bound OIDC ID token for the SA. */
fun claimIdToken(
    brokerBaseUrl: String,
    workerId: String,
    workerSecret: String,
    profileId: String,
    audience: String,
    includeEmail: Boolean,
): WorkerIdTokenClaimResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v2/id-token"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(10))
          .POST(
              HttpRequest.BodyPublishers.ofString(
                  json.encodeToString(IdTokenClaimRequest(profileId, audience, includeEmail))
              )
          )
          .build()

  val response = httpClient.send(request, BodyHandlers.ofString())
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

/** Calls `POST <brokerBaseUrl>/worker/v2/claim`: the token plus the revision's env payload. */
fun claimWorkload(
    brokerBaseUrl: String,
    workerId: String,
    workerSecret: String,
    profileId: String,
): WorkerClaimResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v2/claim"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(10))
          .POST(
              HttpRequest.BodyPublishers.ofString(json.encodeToString(TokenClaimRequest(profileId)))
          )
          .build()

  val response = send(request)
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

/**
 * Calls `POST <brokerBaseUrl>/worker/v2/runs`: records the start of a run (M6-B1), returning its id
 * and the server-controlled heartbeat cadence. Called after a successful claim, right before the
 * workload launches.
 */
fun createRun(
    brokerBaseUrl: String,
    workerId: String,
    workerSecret: String,
    profileId: String,
    revision: Int,
    kind: String,
    imageDigest: String?,
): CreateRunResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v2/runs"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(10))
          .POST(
              HttpRequest.BodyPublishers.ofString(
                  json.encodeToString(CreateRunRequest(profileId, revision, kind, imageDigest))
              )
          )
          .build()

  val response = send(request)
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

/** Calls `POST <brokerBaseUrl>/worker/v2/runs/{runId}/heartbeat` — keeps a run live (204). */
fun heartbeatRun(brokerBaseUrl: String, workerId: String, workerSecret: String, runId: String) {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v2/runs/$runId/heartbeat"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .timeout(Duration.ofSeconds(10))
          .POST(HttpRequest.BodyPublishers.noBody())
          .build()

  val response = send(request)
  if (response.statusCode() != 204) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
}

/** Calls `POST <brokerBaseUrl>/worker/v2/runs/{runId}/end` with the exit code — the terminal report. */
fun endRun(
    brokerBaseUrl: String,
    workerId: String,
    workerSecret: String,
    runId: String,
    exitCode: Int?,
) {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v2/runs/$runId/end"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(10))
          .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(EndRunRequest(exitCode))))
          .build()

  val response = send(request)
  if (response.statusCode() != 204) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
}

private fun errorReason(body: String): String =
    runCatching { json.decodeFromString<WorkerErrorResponse>(body).error }
        .getOrDefault("unknown_error")

fun localHostname(): String? =
    runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
