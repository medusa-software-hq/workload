package software.medusa.workload.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RegisterWorkerResponse(
    val workerId: String,
    val workerSecret: String,
    val confirmationCode: String,
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

@Serializable private data class RegisterWorkerRequest(val name: String, val hostname: String?)

@Serializable private data class TokenClaimRequest(val profileId: String)

@Serializable private data class WorkerErrorResponse(val error: String)

/** [errorCode] is the broker's machine-readable `error` field, e.g. `"not_granted"`. */
class WorkerApiException(val statusCode: Int, val errorCode: String) :
    Exception("Request failed with status $statusCode: $errorCode")

private val json = Json { ignoreUnknownKeys = true }
private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

/** Calls `POST <brokerBaseUrl>/worker/v1/registrations`. */
fun registerWorker(brokerBaseUrl: String, name: String): RegisterWorkerResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v1/registrations"))
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(10))
          .POST(
              HttpRequest.BodyPublishers.ofString(
                  json.encodeToString(
                      RegisterWorkerRequest(name = name, hostname = localHostname())
                  )
              )
          )
          .build()

  val response = httpClient.send(request, BodyHandlers.ofString())
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

/** Calls `GET <brokerBaseUrl>/worker/v1/registrations/self`. */
fun fetchSelfStatus(
    brokerBaseUrl: String,
    workerId: String,
    workerSecret: String,
): SelfStatusResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v1/registrations/self"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()

  val response = httpClient.send(request, BodyHandlers.ofString())
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

/** Calls `POST <brokerBaseUrl>/worker/v1/token`. */
fun claimToken(
    brokerBaseUrl: String,
    workerId: String,
    workerSecret: String,
    profileId: String,
): TokenClaimResponse {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerBaseUrl.trimEnd('/')}/worker/v1/token"))
          .header("Authorization", "Bearer $workerId.$workerSecret")
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(10))
          .POST(
              HttpRequest.BodyPublishers.ofString(json.encodeToString(TokenClaimRequest(profileId)))
          )
          .build()

  val response = httpClient.send(request, BodyHandlers.ofString())
  if (response.statusCode() != 200) {
    throw WorkerApiException(response.statusCode(), errorReason(response.body()))
  }
  return json.decodeFromString(response.body())
}

private fun errorReason(body: String): String =
    runCatching { json.decodeFromString<WorkerErrorResponse>(body).error }
        .getOrDefault("unknown_error")

fun localHostname(): String? =
    runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
