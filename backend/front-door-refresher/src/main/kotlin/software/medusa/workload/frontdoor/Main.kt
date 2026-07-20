package software.medusa.workload.frontdoor

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Cloud Run job (triggered by Cloud Scheduler) that keeps the M4-A6 front-door Worker's
 * credential fresh — keylessly. The org disables service-account keys, so instead of a key the
 * Worker holds a short-lived Google ID token. Running *as* the `front-door-invoker` SA, this job
 * fetches that SA's own ID token (audience = the broker's Cloud Run URL) straight from the GCE
 * metadata server — the very endpoint Beacon emulates — and pushes it into the Worker's
 * `INVOKER_ID_TOKEN` secret via the Cloudflare API. No key and no impersonation: the identity that
 * needs the token is the identity running the job. See apps/front-door and
 * plan/m4/design/00-enrollment-tokens.md.
 */
fun main() {
  val config = RefresherConfig.fromEnv()
  val idToken = fetchOwnIdToken(config.targetAudience)
  pushWorkerSecret(config, idToken)
  println("Refreshed the front-door ${config.workerSecretName} secret.")
}

data class RefresherConfig(
    val targetAudience: String,
    val cfAccountId: String,
    val cfWorkerName: String,
    val cfApiToken: String,
    val workerSecretName: String,
) {
  companion object {
    fun fromEnv(): RefresherConfig =
        RefresherConfig(
            targetAudience = env("TARGET_AUDIENCE"),
            cfAccountId = env("CF_ACCOUNT_ID"),
            cfWorkerName = env("CF_WORKER_NAME"),
            cfApiToken = env("CF_API_TOKEN"),
            workerSecretName = System.getenv("WORKER_SECRET_NAME") ?: "INVOKER_ID_TOKEN",
        )

    private fun env(name: String): String =
        System.getenv(name) ?: error("Missing required environment variable: $name")
  }
}

private const val metadataHost = "http://metadata.google.internal"
private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
private val json = Json { encodeDefaults = true }

/** Fetches this job's own service-account ID token for [audience] from the GCE metadata server. */
private fun fetchOwnIdToken(audience: String): String {
  val uri =
      URI.create(
          "$metadataHost/computeMetadata/v1/instance/service-accounts/default/identity" +
              "?audience=${URLEncoder.encode(audience, StandardCharsets.UTF_8)}"
      )
  val response =
      httpClient.send(
          HttpRequest.newBuilder(uri)
              .header("Metadata-Flavor", "Google")
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build(),
          BodyHandlers.ofString(),
      )
  check(response.statusCode() == 200) { "Metadata identity fetch failed: ${response.statusCode()}" }
  return response.body().trim().also {
    check(it.isNotEmpty()) { "Metadata server returned an empty ID token" }
  }
}

private fun pushWorkerSecret(config: RefresherConfig, idToken: String) {
  val uri =
      URI.create(
          "https://api.cloudflare.com/client/v4/accounts/${config.cfAccountId}" +
              "/workers/scripts/${config.cfWorkerName}/secrets"
      )
  val response =
      httpClient.send(
          HttpRequest.newBuilder(uri)
              .header("Authorization", "Bearer ${config.cfApiToken}")
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(15))
              .PUT(
                  HttpRequest.BodyPublishers.ofString(
                      workerSecretBody(config.workerSecretName, idToken)
                  )
              )
              .build(),
          BodyHandlers.ofString(),
      )
  check(response.statusCode() == 200 && isSuccess(response.body())) {
    "Cloudflare secret update failed: ${response.statusCode()} ${response.body()}"
  }
}

@Serializable
private data class WorkerSecret(
    val name: String,
    val text: String,
    val type: String = "secret_text",
)

/**
 * Builds the Cloudflare "put secret" request body — pure, so the JSON escaping is unit-testable.
 */
internal fun workerSecretBody(name: String, token: String): String =
    json.encodeToString(WorkerSecret(name = name, text = token))

/** True iff a Cloudflare API response body reports `"success": true`. */
internal fun isSuccess(responseBody: String): Boolean =
    runCatching {
          Json.parseToJsonElement(responseBody).jsonObject["success"]?.jsonPrimitive?.booleanOrNull
        }
        .getOrNull() == true
