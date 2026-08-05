package software.medusa.workload.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * A brokered access token as the emulator serves it: the bearer value, the moment it expires, and
 * the target service account it impersonates (all three come from a single `/worker/v2/token`
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
 * One call to the broker's ID-token endpoint for a caller-chosen [audience]. Returns the raw OIDC
 * JWT the metadata `identity` endpoint serves. Injectable for offline testing; when absent, the
 * emulator 404s `identity` as before.
 */
fun interface IdTokenClaimer {
  fun claim(audience: String, includeEmail: Boolean): String
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
  private val refreshes = AtomicLong(0)

  /**
   * How many times this cache has actually (re-)claimed a token from the broker — the Beacon
   * refresh counter (M6-A4 observability). Each increment also emits the
   * `event=beacon.token.refresh` log line, so "did the brokered credential refresh, and how often?"
   * is answerable from a past run's logs alone; this getter is the in-process surface for tests and
   * a future metric on the GCE node.
   */
  val refreshCount: Long
    get() = refreshes.get()

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
        // `cold` = first claim (or after forceExpire); `renewal` = a near-expiry re-claim replacing
        // a live token. The >15-min nightly leg proves a genuine *renewal* happened, not just the
        // initial fetch, so the distinction is carried in the log rather than collapsed into a
        // count.
        val reason = if (existing == null) "cold" else "renewal"
        val fresh = claimer.claim()
        cached = fresh
        val count = refreshes.incrementAndGet()
        log.info(
            "event=beacon.token.refresh reason={} count={} sa={} expires_in={}s",
            reason,
            count,
            fresh.serviceAccountEmail,
            expiresInSeconds(fresh),
        )
        fresh
      }

  /** Seconds until the current token expires, floored at zero — the metadata `expires_in` field. */
  fun expiresInSeconds(token: BrokeredToken): Long =
      Duration.between(clock.instant(), token.expiresAt).seconds.coerceAtLeast(0)

  /** Test/refresh hook: drop the cached token so the next [current] re-claims. */
  fun forceExpire() {
    synchronized(lock) { cached = null }
  }

  private companion object {
    val log = LoggerFactory.getLogger(RefreshingTokenCache::class.java)
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
 * loopback and admits same-host callers (the child is a local process, not a container); `workload
 * run` runs this class inside its own **sidecar container** (see `MetadataSidecar.kt`), bound wide
 * open on that container's network namespace and admitting only the private-range source its
 * dedicated per-run bridge network ever delivers.
 */
class MetadataEmulator(
    private val tokenCache: RefreshingTokenCache,
    bindAddress: InetSocketAddress,
    private val peerAllowed: (InetAddress) -> Boolean,
    private val idTokenClaimer: IdTokenClaimer? = null,
) : AutoCloseable {
  private val server = HttpServer.create(bindAddress, 0)

  /**
   * The `host:port` of the bind address (e.g. `127.0.0.1:49812` for a loopback bind) — the pointer
   * `exec` hands a workload. For a wildcard bind this reports `0.0.0.0`, which a *container* can't
   * dial; `run` uses [port] with the reachable gateway IP instead.
   */
  val hostPort: String
    get() = "${server.address.address.hostAddress}:${server.address.port}"

  /** The bound port — known only after [start]. `run` advertises it against the network gateway. */
  val port: Int
    get() = server.address.port

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
      "$SA_DEFAULT/identity" -> serveIdentity(exchange)
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

  /**
   * `…/service-accounts/default/identity?audience=…&format=[standard|full]` — an OIDC ID token for
   * the profile's target SA, bound to the caller-chosen audience, exactly as GCE serves it (raw
   * JWT, `text/plain`). `format=full` embeds the SA email. A missing audience is a 400, as on GCE.
   * When no [idTokenClaimer] is wired (the broker can't mint ID tokens), this 404s as before.
   */
  private fun serveIdentity(exchange: HttpExchange) {
    val claimer = idTokenClaimer
    if (claimer == null) {
      log.warn(
          "metadata: identity (ID-token) endpoint requested but no ID-token claimer is wired; " +
              "serving 404 (see M4 design note 02, ID-token brokering)",
      )
      respond(exchange, 404, "text/plain", "")
      return
    }

    val params = parseQuery(exchange.requestURI.rawQuery)
    val audience = params["audience"]
    if (audience.isNullOrBlank()) {
      respond(exchange, 400, "text/plain", "non-empty audience parameter required\n")
      return
    }
    val includeEmail = params["format"].equals("full", ignoreCase = true)

    try {
      respond(exchange, 200, "text/plain", claimer.claim(audience, includeEmail))
    } catch (e: WorkerApiException) {
      log.warn("metadata: broker refused ID-token claim ({}); serving 500", e.errorCode)
      respond(exchange, 500, "text/plain", "ID-token claim failed: ${e.errorCode}\n")
    } catch (e: IOException) {
      log.warn("metadata: broker unreachable for ID-token claim ({}); serving 500", e.message)
      respond(exchange, 500, "text/plain", "ID-token claim failed\n")
    }
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
 * Parses a raw query string (`a=1&b=2`) into a decoded key-value map; a keyless pair is dropped.
 */
private fun parseQuery(rawQuery: String?): Map<String, String> {
  if (rawQuery.isNullOrEmpty()) return emptyMap()
  return rawQuery
      .split("&")
      .mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) {
          null
        } else {
          URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8) to
              URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
        }
      }
      .toMap()
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

/**
 * The non-secret env vars that point a workload's Google tooling at the emulator at [hostPort]
 * instead of the real GCE metadata server. These *replace* the injected access-token vars — what
 * `docker inspect` / `ps` now shows is an address, not a credential. All three name the same
 * `host:port`: `GCE_METADATA_HOST` (google-auth java/python/node/go), `GCE_METADATA_IP` (some libs'
 * detection probe), `GCE_METADATA_ROOT` (gcloud).
 */
fun metadataPointerEnv(hostPort: String): Map<String, String> =
    mapOf(
        "GCE_METADATA_HOST" to hostPort,
        "GCE_METADATA_IP" to hostPort,
        "GCE_METADATA_ROOT" to hostPort,
    )

/**
 * The peer predicate the metadata **sidecar** container uses (see `MetadataSidecar.kt`): admit a
 * private-range source only — a container's bridge IP always is one. Belt-and-braces, not the
 * actual isolation boundary: the sidecar sits on a per-run bridge network with exactly one other
 * member (the workload container it serves), so Docker itself never delivers a packet from anywhere
 * else. This, the required `Metadata-Flavor` header, and the sidecar's non-published port are the
 * defense-in-depth layered on top of that network-level guarantee.
 */
fun isTrustedRunPeer(addr: InetAddress): Boolean = addr.isSiteLocalAddress

/** Builds the production [TokenClaimer] that re-claims from [apiBaseUrl] for [profileId]. */
fun brokerTokenClaimer(
    apiBaseUrl: String,
    auth: BrokerAuth,
    profileId: String,
): TokenClaimer = TokenClaimer {
  val claim = claimToken(apiBaseUrl, auth, profileId)
  BrokeredToken(
      accessToken = claim.accessToken,
      expiresAt = Instant.parse(claim.expiresAt),
      serviceAccountEmail = claim.serviceAccount,
  )
}

/**
 * Builds the production [IdTokenClaimer] that claims audience-bound ID tokens from [apiBaseUrl].
 *
 * **No per-audience ID-token cache (M4 follow-up, re-deferred M6-A4).** Unlike the access token —
 * cached and refreshed by [RefreshingTokenCache] because *every* GCP call fetches it — ID tokens go
 * through the `identity` endpoint, which google-auth already caches client-side per audience. No
 * chatty consumer has appeared to justify a second cache here (the reference workload re-mints once
 * per heartbeat, ~30s; the broker call is cheap next to that). Add a cache only if a real consumer
 * makes the `identity` endpoint hot; until then a fresh claim per request is the simpler correct
 * choice, and the extra invalidation surface isn't worth carrying.
 */
fun brokerIdTokenClaimer(
    apiBaseUrl: String,
    auth: BrokerAuth,
    profileId: String,
): IdTokenClaimer = IdTokenClaimer { audience, includeEmail ->
  claimIdToken(apiBaseUrl, auth, profileId, audience, includeEmail).idToken
}
