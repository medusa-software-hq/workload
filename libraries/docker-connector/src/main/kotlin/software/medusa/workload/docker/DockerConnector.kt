package software.medusa.workload.docker

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.util.DomainSocketAddress
import java.nio.file.Path
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * A minimal, in-house Docker Engine API client over a Unix domain socket — the M3
 * `docker-connector`. Story 01 scope: system endpoints (`ping`/`version`/`info`) plus the
 * transport, version negotiation, error model, and lazy initialization every later story builds on.
 *
 * **Lazy by construction.** Building a `DockerConnector` does no I/O and starts no Netty/Armeria
 * machinery; the first endpoint call spins the transport up. So a CLI command that never touches
 * Docker (`--help`, `status`, `token`) pays nothing for holding a connector.
 *
 * **One failure type, no stray output.** Every failure surfaces as a [DockerConnectorException]
 * subclass with a CLI-ready message; the library prints nothing on any thread, including
 * Netty/Armeria event loops (an explicit M3-01 requirement).
 */
class DockerConnector(
    private val config: DockerConnectorConfig = DockerConnectorConfig(),
) : AutoCloseable {

  private val json = Json { ignoreUnknownKeys = true }

  // Lazily created on first use so construction is free. `lazy` is synchronized, so the
  // ClientFactory/WebClient are built exactly once even under concurrent first calls; and
  // `isInitialized()` lets close() stay a no-op when the connector was never used.
  private val transportLazy = lazy { Transport.create(config.socketPath) }

  private val initMutex = Mutex()
  @Volatile private var negotiatedVersion: String? = null

  /**
   * `GET /_ping` — the cheapest liveness check. Returns daemon-advertised header data. Throws
   * [DockerConnectionException] if the daemon can't be reached.
   */
  suspend fun ping(): PingResult {
    val response = request("/_ping")
    if (response.status().code() != 200) {
      throw apiException(response)
    }
    val headers = response.headers()
    return PingResult(
        apiVersion = headers.get("Api-Version").orEmpty(),
        builderVersion = headers.get("Builder-Version"),
        experimental = headers.get("Docker-Experimental")?.toBooleanStrictOrNull() ?: false,
    )
  }

  /** `GET /version`. Also the API-version negotiation probe (queried unversioned). */
  suspend fun version(): VersionInfo {
    val response = request("/version")
    if (response.status().code() != 200) {
      throw apiException(response)
    }
    return decode(response, "version")
  }

  /** `GET /info`, addressed at the negotiated API version. */
  suspend fun info(): SystemInfo {
    val response = request("${versionPrefix()}/info")
    if (response.status().code() != 200) {
      throw apiException(response)
    }
    return decode(response, "info")
  }

  /** The negotiated `/v<major.minor>` path prefix, computed once from a `/version` probe. */
  private suspend fun versionPrefix(): String {
    negotiatedVersion?.let {
      return "/v$it"
    }
    return initMutex.withLock {
      negotiatedVersion?.let {
        return@withLock "/v$it"
      }
      val negotiated = ApiVersion.negotiate(version().apiVersion)
      negotiatedVersion = negotiated
      "/v$negotiated"
    }
  }

  private suspend fun request(path: String): AggregatedHttpResponse =
      try {
        transportLazy.value.webClient.get(path).aggregate().await()
      } catch (e: Exception) {
        throw connectionException(e)
      }

  private inline fun <reified T> decode(response: AggregatedHttpResponse, what: String): T =
      try {
        json.decodeFromString<T>(response.contentUtf8())
      } catch (e: Exception) {
        throw DockerProtocolException("Malformed Docker daemon response for $what", e)
      }

  private fun apiException(response: AggregatedHttpResponse): DockerApiException {
    val message =
        runCatching { json.decodeFromString<DaemonErrorBody>(response.contentUtf8()).message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: response.status().reasonPhrase()
    return DockerApiException(response.status().code(), message)
  }

  /** Maps an Armeria/transport failure into an actionable [DockerConnectionException]. */
  private fun connectionException(e: Exception): DockerConnectionException {
    val causeText = rootCauseMessage(e).lowercase()
    val hint =
        when {
          "permission denied" in causeText ->
              "permission denied — is your user in the 'docker' group? (try `docker info`)"
          "no such file" in causeText || "does not exist" in causeText ->
              "the socket doesn't exist — is Docker installed and running?"
          "connection refused" in causeText -> "connection refused — is the Docker daemon running?"
          else -> "is the Docker daemon running?"
        }
    return DockerConnectionException(
        "Cannot reach the Docker daemon at ${config.socketPath}: $hint",
        e,
    )
  }

  override fun close() {
    // Closing an unused connector must stay free (no Netty ever started).
    if (transportLazy.isInitialized()) {
      transportLazy.value.close()
    }
  }

  /** Test-only: has the Armeria/Netty transport been spun up yet? Proves lazy-init behavior. */
  internal fun isTransportInitializedForTest(): Boolean = transportLazy.isInitialized()

  private fun rootCauseMessage(throwable: Throwable): String {
    var current: Throwable = throwable
    while (true) {
      val next = current.cause ?: break
      if (next === current) break
      current = next
    }
    return current.message ?: current::class.simpleName.orEmpty()
  }

  /** Owns the Armeria client + its dedicated [ClientFactory] so [close] can release event loops. */
  private class Transport
  private constructor(
      val webClient: WebClient,
      private val factory: ClientFactory,
  ) : AutoCloseable {
    override fun close() {
      factory.close()
    }

    companion object {
      fun create(socketPath: String): Transport {
        val address = DomainSocketAddress.of(Path.of(socketPath))
        val factory = ClientFactory.builder().build()
        val webClient = WebClient.builder("http://" + address.authority()).factory(factory).build()
        return Transport(webClient, factory)
      }
    }
  }
}
