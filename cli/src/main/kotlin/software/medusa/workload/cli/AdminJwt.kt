package software.medusa.workload.cli

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Just enough JWT to read claims out of a Google ID token *we already trust* — it came straight
 * from Google's token endpoint over TLS, so this is not a verification path (the API re-verifies
 * the signature). Reads only the payload; never validates.
 */
object AdminJwt {
  private val json = Json { ignoreUnknownKeys = true }

  private fun payload(idToken: String): kotlinx.serialization.json.JsonObject {
    val parts = idToken.split(".")
    require(parts.size == 3) { "Not a JWT" }
    val decoded = Base64.getUrlDecoder().decode(parts[1])
    return json.parseToJsonElement(String(decoded, Charsets.UTF_8))
        as kotlinx.serialization.json.JsonObject
  }

  /** The `email` claim, or null if absent/unparseable. */
  fun email(idToken: String): String? =
      runCatching { payload(idToken)["email"]?.jsonPrimitive?.content }.getOrNull()

  /** The `exp` claim as epoch seconds, or null if absent/unparseable. */
  fun expiresAtEpochSec(idToken: String): Long? =
      runCatching { payload(idToken)["exp"]?.jsonPrimitive?.longOrNull }.getOrNull()
}
