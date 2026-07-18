package software.medusa.workload.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * A brokered access token as the emulator serves it: the bearer value, the moment it expires, and
 * the target service account it impersonates (all three come from a single `/worker/v1/token`
 * claim).
 */
data class BrokeredToken(
    val accessToken: String,
    val expiresAt: Instant,
    val serviceAccountEmail: String,
)

/** One call to the broker's token endpoint. Injectable so the emulator can be tested offline. */
fun interface TokenClaimer {
  fun claim(): BrokeredToken
}

/**
 * Caches a [BrokeredToken] and re-claims it when it nears expiry — the emulation of a metadata
 * server's "mint on demand" behaviour. Refresh is **single-flight**: concurrent callers that arrive
 * while the cache is cold (or stale) share one broker call rather than each firing their own.
 *
 * The single-flight guarantee is the plain-`synchronized` kind: callers serialize through
 * [current], so only the first past the freshness check actually claims; the rest observe the
 * freshly-cached token and return it. For an in-process, low-QPS metadata endpoint that is the
 * simplest correct design — no in-flight future to share, no double-checked subtlety.
 */
class RefreshingTokenCache(
    private val claimer: TokenClaimer,
    private val refreshSkew: Duration = Duration.ofMinutes(2),
    private val clock: Clock = Clock.systemUTC(),
) {
  private val lock = Any()
  private var cached: BrokeredToken? = null

  /**
   * The current token, re-claiming if the cached one is missing or within [refreshSkew] of expiry.
   */
  fun current(): BrokeredToken =
      synchronized(lock) {
        val existing = cached
        if (
            existing != null && Duration.between(clock.instant(), existing.expiresAt) > refreshSkew
        ) {
          return existing
        }
        val fresh = claimer.claim()
        cached = fresh
        fresh
      }

  /** Seconds until the current token expires, floored at zero — the metadata `expires_in` field. */
  fun expiresInSeconds(token: BrokeredToken): Long =
      Duration.between(clock.instant(), token.expiresAt).seconds.coerceAtLeast(0)

  /** Test/refresh hook: drop the cached token so the next [current] re-claims. */
  fun forceExpire() {
    synchronized(lock) { cached = null }
  }
}

@Serializable
private data class MetadataTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("token_type") val tokenType: String,
)

/**
 * An in-process emulation of the GCE metadata server
 * (`http://metadata.google.internal/computeMetadata/v1/…`), backed by the broker. Google auth
 * libraries and `gcloud` inside a workload talk to it exactly as they would on a real VM: they
 * detect it via the `Metadata-Flavor: Google` header, then fetch — and transparently refresh —
 * brokered tokens from it. The credential therefore never lands in the container's environment; a
 * non-secret pointer (`GCE_METADATA_HOST=<addr>`) does.
 *
 * Binding and peer admission are injected because they differ per transport: `workload exec` binds
 * loopback and admits same-host callers; `workload run` binds a per-run bridge gateway and admits
 * only the started container's IP (wired in B3/B4).
 */
class MetadataEmulator(
    private val tokenCache: RefreshingTokenCache,
    bindAddress: InetSocketAddress,
    private val peerAllowed: (InetAddress) -> Boolean,
) : AutoCloseable {
  private val server = HttpServer.create(bindAddress, 0)

  /** The `host:port` a workload points at via `GCE_METADATA_HOST` — known only after [start]. */
  val hostPort: String
    get() = "${server.address.address.hostAddress}:${server.address.port}"

  init {
    server.createContext("/") { exchange -> handle(exchange) }
    // A small pool so concurrent in-container token fetches are genuinely concurrent (and thus
    // actually exercise the cache's single-flight path) rather than queued behind one thread.
    server.executor = Executors.newCachedThreadPool()
  }

  fun start() {
    server.start()
  }

  override fun close() = server.stop(0)

  private fun handle(exchange: HttpExchange) {
    try {
      if (!peerAllowed(exchange.remoteAddress.address)) {
        respond(exchange, 403, "text/plain", "Forbidden\n")
        return
      }
      route(exchange)
    } catch (e: IOException) {
      // A dropped connection (client gave up mid-response) is routine; don't let it escape the
      // handler thread. Nothing else useful to do.
      log.debug("metadata request I/O error: {}", e.message)
      runCatching { exchange.close() }
    } finally {
      exchange.close()
    }
  }

  @Suppress("ReturnCount")
  private fun route(exchange: HttpExchange) {
    val path = exchange.requestURI.path

    // Bare-root detection ping: google-auth checks only the response's Metadata-Flavor header, and
    // sends the request header itself — so don't gate the root on it.
    if (path == "/") {
      respond(exchange, 200, "text/plain", "computeMetadata/\n")
      return
    }

    if (!path.startsWith("/computeMetadata/")) {
      respond(exchange, 404, "text/plain", "")
      return
    }

    // Everything under /computeMetadata/ requires the flavor header, exactly as real GCE does.
    if (!exchange.requestHeaders.getFirst("Metadata-Flavor").equals("Google", ignoreCase = true)) {
      respond(
          exchange,
          403,
          "text/plain",
          "Missing Metadata-Flavor:Google header.\n",
      )
      return
    }

    when (path) {
      "/computeMetadata/",
      "/computeMetadata/v1",
      "/computeMetadata/v1/" -> respond(exchange, 200, "text/plain", "instance/\nproject/\n")
      "$INSTANCE/service-accounts/",
      "$INSTANCE/service-accounts" -> respond(exchange, 200, "text/plain", "default/\n")
      "$SA_DEFAULT/",
      SA_DEFAULT ->
          respond(exchange, 200, "text/plain", "aliases\nemail\nidentity\nscopes\ntoken\n")
      "$SA_DEFAULT/token" -> serveToken(exchange)
      "$SA_DEFAULT/email" -> serveWithToken(exchange) { it.serviceAccountEmail }
      "$SA_DEFAULT/scopes" -> respond(exchange, 200, "text/plain", "$CLOUD_PLATFORM_SCOPE\n")
      "$SA_DEFAULT/aliases" -> respond(exchange, 200, "text/plain", "default\n")
      "$SA_DEFAULT/identity" -> serveIdentityUnsupported(exchange)
      "$INSTANCE/universe/universe-domain" -> respond(exchange, 200, "text/plain", "googleapis.com")
      "$PROJECT/project-id" -> serveProjectId(exchange)
      "$PROJECT/numeric-project-id" -> serveNumericProjectIdUnsupported(exchange)
      else -> respond(exchange, 404, "text/plain", "")
    }
  }

  private fun serveToken(exchange: HttpExchange) =
      withClaim(exchange) { token ->
        val body =
            json.encodeToString(
                MetadataTokenResponse(
                    accessToken = token.accessToken,
                    expiresIn = tokenCache.expiresInSeconds(token),
                    tokenType = "Bearer",
                )
            )
        respond(exchange, 200, "application/json", body)
      }

  private fun serveWithToken(exchange: HttpExchange, project: (BrokeredToken) -> String) =
      withClaim(exchange) { respond(exchange, 200, "text/plain", project(it)) }

  private fun serveProjectId(exchange: HttpExchange) =
      withClaim(exchange) { token ->
        val projectId = projectIdFromServiceAccount(token.serviceAccountEmail)
        if (projectId == null) {
          log.warn(
              "metadata: cannot derive project-id from service account '{}' (not a " +
                  "*.iam.gserviceaccount.com identity); serving 404",
              token.serviceAccountEmail,
          )
          respond(exchange, 404, "text/plain", "")
        } else {
          respond(exchange, 200, "text/plain", projectId)
        }
      }

  /**
   * Runs [block] with a freshly-resolved token, translating a broker failure into a 500 so the
   * calling library's GCP call fails — which is exactly how a revoked worker loses access mid-run.
   */
  private fun withClaim(exchange: HttpExchange, block: (BrokeredToken) -> Unit) {
    val token =
        try {
          tokenCache.current()
        } catch (e: WorkerApiException) {
          log.warn("metadata: broker refused token claim ({}); serving 500", e.errorCode)
          respond(exchange, 500, "text/plain", "Token claim failed: ${e.errorCode}\n")
          return
        } catch (e: IOException) {
          log.warn("metadata: broker unreachable for token claim ({}); serving 500", e.message)
          respond(exchange, 500, "text/plain", "Token claim failed\n")
          return
        }
    block(token)
  }

  private fun serveIdentityUnsupported(exchange: HttpExchange) {
    log.warn(
        "metadata: identity (ID-token) endpoint requested but unsupported — the broker mints " +
            "access tokens only; serving 404 (see M4 design note 02, ID-token brokering)",
    )
    respond(exchange, 404, "text/plain", "")
  }

  private fun serveNumericProjectIdUnsupported(exchange: HttpExchange) {
    log.warn(
        "metadata: numeric-project-id requested but unavailable — not derivable from the service " +
            "account without an extra API call; serving 404",
    )
    respond(exchange, 404, "text/plain", "")
  }

  private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    // Real GCE stamps this on every response; detection depends on it.
    exchange.responseHeaders.add("Metadata-Flavor", "Google")
    exchange.responseHeaders.add("Content-Type", contentType)
    exchange.responseHeaders.add("Server", "Metadata Server for Workload")
    // A body length of 0 must be signalled with -1, not 0 (which means "chunked, unknown length").
    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) {
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  private companion object {
    val log = LoggerFactory.getLogger(MetadataEmulator::class.java)
    const val INSTANCE = "/computeMetadata/v1/instance"
    const val PROJECT = "/computeMetadata/v1/project"
    const val SA_DEFAULT = "/computeMetadata/v1/instance/service-accounts/default"
    const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
    val json = Json { encodeDefaults = true }
  }
}

/**
 * Derives the GCP project id from a user-managed service account email
 * (`name@PROJECT_ID.iam.gserviceaccount.com` → `PROJECT_ID`), or null if [email] isn't that shape
 * (e.g. a default compute SA, whose project is only the *numeric* id).
 */
fun projectIdFromServiceAccount(email: String): String? {
  val suffix = ".iam.gserviceaccount.com"
  if (!email.endsWith(suffix)) return null
  val project = email.substringAfter('@', "").removeSuffix(suffix)
  return project.ifEmpty { null }
}

/** Builds the production [TokenClaimer] that re-claims from the broker for [profileId]. */
fun brokerTokenClaimer(config: WorkloadConfig, profileId: String): TokenClaimer = TokenClaimer {
  val claim = claimToken(config.brokerBaseUrl, config.workerId, config.workerSecret, profileId)
  BrokeredToken(
      accessToken = claim.accessToken,
      expiresAt = Instant.parse(claim.expiresAt),
      serviceAccountEmail = claim.serviceAccount,
  )
}
