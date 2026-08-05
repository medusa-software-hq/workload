package software.medusa.workload.runtime

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration

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
 * The default broker-auth strategy: a worker's long-lived `id.secret` credential pair, exactly as
 * `workload worker register` mints it and stores it in `config.json`.
 */
class SecretBrokerAuth(private val workerId: String, private val workerSecret: String) :
    BrokerAuth {
  override fun authorizationHeader(): String = "Bearer $workerId.$workerSecret"
}

private val metadataHttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

/**
 * The worker plane's other broker-auth strategy (M7): a GCE VM's own service-account identity,
 * fetched fresh from the metadata server on every call — no on-disk secret at all. The broker
 * verifies the returned ID token against its Terraform-managed node allowlist (see
 * `GooglePrincipalVerifier` on the backend), rather than looking it up in the fleet store.
 *
 * [metadataHost] defaults to the real GCE metadata server; tests point it at a stub HTTP server
 * instead of mutating the JVM's environment (`GCE_METADATA_HOST` is the convention Google's own
 * client libraries respect, but this class talks to the metadata server directly, so it doesn't
 * need that indirection).
 */
class GceMetadataBrokerAuth(
    private val audience: String,
    private val metadataHost: String = "http://metadata.google.internal",
) : BrokerAuth {
  override fun authorizationHeader(): String = "Bearer ${fetchIdentityToken()}"

  private fun fetchIdentityToken(): String {
    val uri =
        URI.create(
            "$metadataHost/computeMetadata/v1/instance/service-accounts/default/identity" +
                "?audience=${URLEncoder.encode(audience, StandardCharsets.UTF_8)}"
        )
    val response =
        metadataHttpClient.send(
            HttpRequest.newBuilder(uri)
                .header("Metadata-Flavor", "Google")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            BodyHandlers.ofString(),
        )
    check(response.statusCode() == 200) {
      "Metadata identity fetch failed: ${response.statusCode()}"
    }
    return response.body().trim().also {
      check(it.isNotEmpty()) { "Metadata server returned an empty ID token" }
    }
  }
}

/**
 * Which identity a worker-plane node authenticates with — declared by whoever launches the node
 * process (not inferred from its environment), so [brokerAuthFor] knows which [BrokerAuth] to build
 * and the broker knows which check that node's identity is subject to: a worker secret against the
 * fleet store, or a GCE service-account identity against the node allowlist.
 */
enum class NodeKind {
  /** A registered worker's long-lived `id.secret` credential — see [SecretBrokerAuth]. */
  SECRET,
  /**
   * A GCE VM's own service-account identity, fetched from the metadata server — see
   * [GceMetadataBrokerAuth].
   */
  GCE_METADATA,
}

/**
 * Builds the [BrokerAuth] for a declared [nodeKind]. [audience] (the broker's own base URL — what a
 * metadata-server ID token must name) is only consulted for [NodeKind.GCE_METADATA];
 * [workerId]/[workerSecret] only for [NodeKind.SECRET].
 */
fun brokerAuthFor(
    nodeKind: NodeKind,
    audience: String,
    workerId: String? = null,
    workerSecret: String? = null,
): BrokerAuth =
    when (nodeKind) {
      NodeKind.SECRET ->
          SecretBrokerAuth(
              workerId ?: error("workerId is required for NodeKind.SECRET"),
              workerSecret ?: error("workerSecret is required for NodeKind.SECRET"),
          )
      NodeKind.GCE_METADATA -> GceMetadataBrokerAuth(audience)
    }
