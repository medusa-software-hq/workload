package software.medusa.workload.runtime

/**
 * How a request to the broker's worker-plane API (claim, run lifecycle, self-status, ...)
 * authenticates itself. Pluggable so a caller isn't tied to the CLI's on-disk `id.secret` worker
 * credential — e.g. a node running the pipeline under a different identity can supply its own
 * strategy — while every call site in this library stays agnostic to how the header was produced.
 */
fun interface BrokerAuth {
  /** The `Authorization` header value to send with the request, e.g. `Bearer <token>`. */
  fun authorizationHeader(): String
}

/**
 * The default, and so far only, broker-auth strategy: a worker's long-lived `id.secret` credential
 * pair, exactly as `workload worker register` mints it and stores it in `config.json`.
 */
class SecretBrokerAuth(private val workerId: String, private val workerSecret: String) :
    BrokerAuth {
  override fun authorizationHeader(): String = "Bearer $workerId.$workerSecret"
}
