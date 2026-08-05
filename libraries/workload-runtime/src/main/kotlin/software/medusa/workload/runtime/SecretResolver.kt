package software.medusa.workload.runtime

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val secretManagerBaseUrl = "https://secretmanager.googleapis.com/v1"
private const val redactedValue = "***"

@Serializable private data class AccessSecretVersionResponse(val payload: SecretPayload)

@Serializable private data class SecretPayload(val data: String)

/** [envVarName] and [resourceName] name what failed; never carries a secret value. */
class SecretResolutionException(val envVarName: String, val resourceName: String, message: String) :
    Exception(message)

private val json = Json { ignoreUnknownKeys = true }
private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

/**
 * Resolves each [secretEnvVars] reference (env var name -> Secret Manager resource name) to its
 * plaintext value via the Secret Manager REST API, authenticated with [accessToken] — the same
 * brokered, impersonated token used to claim the profile. Plain `java.net.http`, not the GCP Java
 * client: the CLI has no heavyweight GCP dependency today and this shouldn't be the first one.
 *
 * All-or-nothing: the first failure aborts before any other secret is attempted, and before
 * anything downstream runs — a partially-resolved environment is worse than none. The access itself
 * is attributable to the target service account in Cloud Audit Logs, since [accessToken]
 * impersonates it; the broker never sees these values.
 *
 * Resolved values are returned to the caller only — never logged, never written to disk by this
 * function. Callers must observe the same discipline (see [redactedEnvSummary] for debug output).
 */
fun resolveSecrets(
    secretEnvVars: Map<String, String>,
    accessToken: String,
    baseUrl: String = secretManagerBaseUrl,
): Map<String, String> = secretEnvVars.mapValues { (envVarName, resourceName) ->
  accessSecretVersion(envVarName, resourceName, accessToken, baseUrl)
}

/** Env vars safe to print: plain values as-is, secret-backed ones redacted (`API_KEY=***`). */
fun redactedEnvSummary(
    envVars: Map<String, String>,
    secretEnvVars: Map<String, String>,
): Map<String, String> = envVars + secretEnvVars.keys.associateWith { redactedValue }

private fun accessSecretVersion(
    envVarName: String,
    resourceName: String,
    accessToken: String,
    baseUrl: String,
): String {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create("$baseUrl/$resourceName:access"))
          .header("Authorization", "Bearer $accessToken")
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()

  val response =
      try {
        httpClient.send(request, BodyHandlers.ofString())
      } catch (e: IOException) {
        throw SecretResolutionException(
            envVarName,
            resourceName,
            "Couldn't reach Secret Manager to resolve '$envVarName' ($resourceName): ${e.message}",
        )
      }

  when (response.statusCode()) {
    200 -> {}
    403 ->
        throw SecretResolutionException(
            envVarName,
            resourceName,
            "Access denied resolving '$envVarName' ($resourceName). Ask the project that owns " +
                "this secret to grant the profile's service account " +
                "roles/secretmanager.secretAccessor on it.",
        )
    404 ->
        throw SecretResolutionException(
            envVarName,
            resourceName,
            "Secret '$envVarName' ($resourceName) not found. Check the resource name, and that " +
                "the version hasn't been destroyed.",
        )
    else ->
        throw SecretResolutionException(
            envVarName,
            resourceName,
            "Failed to resolve '$envVarName' ($resourceName): HTTP ${response.statusCode()}",
        )
  }

  val decoded =
      try {
        json.decodeFromString<AccessSecretVersionResponse>(response.body())
      } catch (e: Exception) {
        throw SecretResolutionException(
            envVarName,
            resourceName,
            "Malformed Secret Manager response resolving '$envVarName' ($resourceName)",
        )
      }

  return String(Base64.getDecoder().decode(decoded.payload.data), Charsets.UTF_8)
}
