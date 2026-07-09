package software.medusa.workload.server

import java.util.UUID

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
