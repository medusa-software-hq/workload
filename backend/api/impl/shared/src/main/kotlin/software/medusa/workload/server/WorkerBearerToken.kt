package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import java.security.MessageDigest
import java.util.UUID

private const val httpAuthorizationHeaderName = "Authorization"
private const val bearerPrefix = "Bearer "

internal fun extractBearerToken(req: HttpRequest): String? {
  val header = req.headers().get(httpAuthorizationHeaderName) ?: return null
  if (!header.startsWith(bearerPrefix)) return null
  return header.removePrefix(bearerPrefix).trim().takeIf { it.isNotEmpty() }
}

internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

internal fun constantTimeEquals(a: String, b: String): Boolean =
    constantTimeEquals(a.toByteArray(), b.toByteArray())

/**
 * Parses `<workerId>.<workerSecret>` (the worker plane's bearer-token format). `.` is a safe
 * separator: worker secrets are unpadded base64url, whose alphabet never contains `.`.
 */
internal fun parseWorkerBearerToken(token: String): Pair<WorkerId, String>? {
  val separatorIndex = token.indexOf('.')
  if (separatorIndex < 0) return null

  val secret = token.substring(separatorIndex + 1)
  if (secret.isEmpty()) return null

  val workerId =
      runCatching { UUID.fromString(token.substring(0, separatorIndex)) }.getOrNull() ?: return null

  return WorkerId(workerId) to secret
}
