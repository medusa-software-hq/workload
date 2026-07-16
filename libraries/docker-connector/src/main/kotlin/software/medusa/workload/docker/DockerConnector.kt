package software.medusa.workload.docker

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.util.DomainSocketAddress
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * A minimal, in-house Docker Engine API client over a Unix domain socket — the M3
 * `docker-connector`. Endpoint groups hang off it: [containers] (M3-02); system endpoints
 * (`ping`/`version`/`info`) are here directly.
 *
 * **Lazy by construction.** Building a `DockerConnector` does no I/O and starts no Netty/Armeria
 * machinery; the first endpoint call spins the transport up. So a CLI command that never touches
 * Docker pays nothing for holding a connector.
 *
 * **One failure type, no stray output.** Every failure surfaces as a [DockerConnectorException]
 * subclass with a CLI-ready message; the library prints nothing on any thread, including
 * Netty/Armeria event loops.
 */
class DockerConnector(
    private val config: DockerConnectorConfig = DockerConnectorConfig(),
) : DockerEngine, AutoCloseable {

  override val json: Json = Json { ignoreUnknownKeys = true }

  /** Container lifecycle endpoints (create/start/wait/stop/remove/list/inspect). */
  val containers: ContainerApi = ContainerApi(this)

  /** Container log streaming (stdout/stderr demux, follow mode). */
  val logs: LogApi = LogApi(this, containers)

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
    val response = exchange(HttpMethod.GET, "/_ping")
    response.ensureSuccess(json)
    val headers = response.headers()
    return PingResult(
        apiVersion = headers.get("Api-Version").orEmpty(),
        builderVersion = headers.get("Builder-Version"),
        experimental = headers.get("Docker-Experimental")?.toBooleanStrictOrNull() ?: false,
    )
  }

  /** `GET /version`. Also the API-version negotiation probe (queried unversioned). */
  suspend fun version(): VersionInfo {
    val response = exchange(HttpMethod.GET, "/version")
    response.ensureSuccess(json)
    return response.decodeBody(json, "version")
  }

  /** `GET /info`, addressed at the negotiated API version. */
  suspend fun info(): SystemInfo {
    val response = exchange(HttpMethod.GET, versionedPath("/info"))
    response.ensureSuccess(json)
    return response.decodeBody(json, "info")
  }

  override suspend fun versionedPath(path: String): String = "${versionPrefix()}$path"

  override suspend fun exchange(
      method: HttpMethod,
      pathWithQuery: String,
      jsonBody: String?,
  ): AggregatedHttpResponse =
      try {
        val headersBuilder = RequestHeaders.builder(method, pathWithQuery)
        val request =
            if (jsonBody != null) {
              HttpRequest.of(
                  headersBuilder.contentType(MediaType.JSON).build(),
                  HttpData.ofUtf8(jsonBody),
              )
            } else {
              HttpRequest.of(headersBuilder.build())
            }
        transportLazy.value.webClient.execute(request).aggregate().await()
      } catch (e: Exception) {
        throw connectionException(e)
      }

  override fun streamBytes(method: HttpMethod, pathWithQuery: String): Flow<ByteArray> = flow {
    val response =
        try {
          val request = HttpRequest.of(RequestHeaders.of(method, pathWithQuery))
          transportLazy.value.webClient.execute(request)
        } catch (e: Exception) {
          throw connectionException(e)
        }

    // abort() on the underlying response in a finally guarantees the connection is released whether
    // the collector completes, throws, or cancels mid-follow — no dangling socket. It's idempotent,
    // so aborting an already-drained response is harmless.
    try {
      val split = response.split()
      val headers =
          try {
            split.headers().await()
          } catch (e: Exception) {
            throw connectionException(e)
          }

      val code = headers.status().code()
      if (code !in SUCCESS_RANGE) {
        // Drain the (small) error body so we can surface the daemon's own message, then stop.
        val body =
            try {
              split.body().collect().await().joinToString(separator = "") { it.toStringUtf8() }
            } catch (e: Exception) {
              throw connectionException(e)
            }
        val message =
            runCatching { json.decodeFromString<DaemonErrorBody>(body).message }
                .getOrNull()
                ?.takeIf { it.isNotBlank() } ?: headers.status().reasonPhrase()
        throw DockerApiException(code, message)
      }

      // Emit body chunks as they arrive. asFlow() (kotlinx-coroutines-reactive) honors backpressure
      // and, on collector cancellation, cancels the subscription; the finally below then aborts the
      // response so the socket is closed and a cancelled follow leaks nothing.
      emitAll(split.body().asFlow().map { it.array() })
    } finally {
      response.abort()
    }
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

  private companion object {
    val SUCCESS_RANGE = 200..299
  }
}
