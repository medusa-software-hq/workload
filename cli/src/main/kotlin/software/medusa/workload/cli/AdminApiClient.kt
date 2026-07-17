package software.medusa.workload.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val apiJson = Json { ignoreUnknownKeys = true }

/** A profile as returned by FleetService.ListProfiles (proto3 JSON — camelCase field names). */
@Serializable
data class AdminProfile(
    val profileId: String = "",
    val displayName: String = "",
    val latestRevision: Int = 0,
    val archived: Boolean = false,
    val createdAt: String = "",
)

@Serializable
internal data class ListProfilesResponse(val profiles: List<AdminProfile> = emptyList())

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
      apiJson
          .decodeFromString<ListProfilesResponse>(
              post("/medusa.workload.v1.FleetService/ListProfiles", "{}")
          )
          .profiles

  private fun post(path: String, body: String): String {
    val request =
        HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
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
