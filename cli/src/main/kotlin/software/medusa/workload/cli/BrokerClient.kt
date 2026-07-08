package software.medusa.workload.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BrokerTokenResponse(
    val accessToken: String,
    val expiresAt: String,
    val serviceAccount: String,
)

@Serializable private data class BrokerErrorResponse(val error: String)

class BrokerRequestException(message: String) : Exception(message)

private val json = Json { ignoreUnknownKeys = true }

/** Calls `POST <brokerUrl>/worker/v1/token` and returns the parsed success response. */
fun fetchBrokerToken(brokerUrl: String, bootstrapToken: String): BrokerTokenResponse {
  val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("${brokerUrl.trimEnd('/')}/worker/v1/token"))
          .header("Authorization", "Bearer $bootstrapToken")
          .timeout(Duration.ofSeconds(10))
          .POST(HttpRequest.BodyPublishers.noBody())
          .build()

  val response = client.send(request, BodyHandlers.ofString())

  if (response.statusCode() != 200) {
    val error =
        runCatching { json.decodeFromString<BrokerErrorResponse>(response.body()).error }
            .getOrDefault("unknown_error")
    throw BrokerRequestException(
        "Broker request failed with status ${response.statusCode()}: $error"
    )
  }

  return json.decodeFromString(response.body())
}
