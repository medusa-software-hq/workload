package software.medusa.workload.server

/**
 * Configuration for the worker bootstrap-token broker endpoint (`/worker/v1/token`).
 *
 * @param bootstrapToken the expected bearer token, loaded from Secret Manager at startup.
 * @param targetServiceAccountEmail the GCP service account to impersonate via generateAccessToken.
 * @param tokenLifetimeSeconds requested access-token lifetime (IAM caps this; default 900s).
 */
data class WorkerTokenBrokerConfig(
    val bootstrapToken: String,
    val targetServiceAccountEmail: String,
    val tokenLifetimeSeconds: Long = 900L,
)
