package software.medusa.workload.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

internal val workerJson = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}

@Serializable internal data class WorkerErrorResponse(val error: String)

/**
 * A single structured audit-log line for the worker plane. Extend with more optional fields as new
 * call sites need them — never add anything that could carry a secret or an Authorization header
 * value.
 */
@Serializable
internal data class AuditLogEntry(
    val event: String,
    val requestId: String,
    val timestamp: String,
    val sourceIp: String,
    val result: String,
    val workerId: String? = null,
    val profileId: String? = null,
    val revision: Int? = null,
    val targetServiceAccount: String? = null,
    val expiresAt: String? = null,
    /** ID-token mints record the caller-chosen audience — the one place we exceed GCE's audit. */
    val audience: String? = null,
    // The surrogate id of an enrollment token (not the token or its hash — safe to log).
    val enrollmentTokenId: String? = null,
    val reason: String? = null,
)

private val auditLogger = LoggerFactory.getLogger("worker.token.broker.audit")

internal fun audit(entry: AuditLogEntry) {
  auditLogger.info(workerJson.encodeToString(entry))
}
