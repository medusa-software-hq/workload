package software.medusa.workload.runtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

private val httpClient = HttpClient.newHttpClient()
private val json = Json { ignoreUnknownKeys = true }

private fun token(
    access: String = "ya29.test",
    expiresAt: Instant = Instant.now().plusSeconds(3600),
    sa: String = "target@my-project.iam.gserviceaccount.com",
) = BrokeredToken(access, expiresAt, sa)

private fun emulator(
    claimer: TokenClaimer,
    refreshSkew: Duration = Duration.ofMinutes(2),
    clock: Clock = Clock.systemUTC(),
    peerAllowed: (InetAddress) -> Boolean = { it.isLoopbackAddress },
    idTokenClaimer: IdTokenClaimer? = null,
): MetadataEmulator =
    MetadataEmulator(
            RefreshingTokenCache(claimer, refreshSkew, clock),
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            peerAllowed,
            idTokenClaimer,
        )
        .also { it.start() }

private fun get(
    emu: MetadataEmulator,
    path: String,
    flavor: Boolean = true,
): HttpResponse<String> {
  val builder = HttpRequest.newBuilder().uri(URI.create("http://${emu.hostPort}$path")).GET()
  if (flavor) builder.header("Metadata-Flavor", "Google")
  return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
}

class MetadataEmulatorTest {
  @Test
  fun `token endpoint serves access token, remaining lifetime, and Bearer type`() {
    emulator({ token(access = "ya29.abc", expiresAt = Instant.now().plusSeconds(900)) }).use { emu
      ->
      val res = get(emu, "/computeMetadata/v1/instance/service-accounts/default/token")
      assertEquals(200, res.statusCode())
      assertEquals("Google", res.headers().firstValue("Metadata-Flavor").get())
      val body = json.parseToJsonElement(res.body())
      val obj = body as kotlinx.serialization.json.JsonObject
      assertEquals("ya29.abc", obj["access_token"]!!.jsonPrimitive.content)
      assertEquals("Bearer", obj["token_type"]!!.jsonPrimitive.content)
      val expiresIn = obj["expires_in"]!!.jsonPrimitive.content.toLong()
      assertTrue(expiresIn in 890..900, "expires_in was $expiresIn")
    }
  }

  @Test
  fun `email, scopes, aliases, and project-id reflect the service account`() {
    emulator({ token(sa = "runner@proj-42.iam.gserviceaccount.com") }).use { emu ->
      assertEquals(
          "runner@proj-42.iam.gserviceaccount.com",
          get(emu, "/computeMetadata/v1/instance/service-accounts/default/email").body(),
      )
      assertEquals(
          "https://www.googleapis.com/auth/cloud-platform\n",
          get(emu, "/computeMetadata/v1/instance/service-accounts/default/scopes").body(),
      )
      assertEquals(
          "proj-42",
          get(emu, "/computeMetadata/v1/project/project-id").body(),
      )
      assertEquals(
          "googleapis.com",
          get(emu, "/computeMetadata/v1/instance/universe/universe-domain").body(),
      )
    }
  }

  @Test
  fun `root ping is answered with the Metadata-Flavor header and no flavor requirement`() {
    emulator({ token() }).use { emu ->
      val res = get(emu, "/", flavor = false)
      assertEquals(200, res.statusCode())
      assertEquals("Google", res.headers().firstValue("Metadata-Flavor").get())
    }
  }

  @Test
  fun `computeMetadata paths without the flavor header are refused with 403`() {
    emulator({ token() }).use { emu ->
      val res =
          get(emu, "/computeMetadata/v1/instance/service-accounts/default/token", flavor = false)
      assertEquals(403, res.statusCode())
    }
  }

  @Test
  fun `identity endpoint 404s when no ID-token claimer is wired`() {
    emulator({ token() }).use { emu ->
      assertEquals(
          404,
          get(emu, "/computeMetadata/v1/instance/service-accounts/default/identity?audience=x")
              .statusCode(),
      )
    }
  }

  @Test
  fun `identity endpoint serves the ID token for the requested audience`() {
    val claimer = IdTokenClaimer { audience, includeEmail ->
      "jwt-for-$audience-email=$includeEmail"
    }
    emulator({ token() }, idTokenClaimer = claimer).use { emu ->
      val standard =
          get(emu, "/computeMetadata/v1/instance/service-accounts/default/identity?audience=flow")
      assertEquals(200, standard.statusCode())
      assertEquals("jwt-for-flow-email=false", standard.body())

      val full =
          get(
              emu,
              "/computeMetadata/v1/instance/service-accounts/default/identity?audience=flow&format=full",
          )
      assertEquals("jwt-for-flow-email=true", full.body())
    }
  }

  @Test
  fun `identity endpoint requires an audience`() {
    val claimer = IdTokenClaimer { _, _ -> "jwt" }
    emulator({ token() }, idTokenClaimer = claimer).use { emu ->
      assertEquals(
          400,
          get(emu, "/computeMetadata/v1/instance/service-accounts/default/identity").statusCode(),
      )
    }
  }

  @Test
  fun `identity endpoint 500s when the broker refuses the ID-token claim`() {
    val claimer = IdTokenClaimer { _, _ -> throw WorkerApiException(403, "not_granted") }
    emulator({ token() }, idTokenClaimer = claimer).use { emu ->
      assertEquals(
          500,
          get(emu, "/computeMetadata/v1/instance/service-accounts/default/identity?audience=x")
              .statusCode(),
      )
    }
  }

  @Test
  fun `numeric-project-id is a documented 404`() {
    emulator({ token() }).use { emu ->
      assertEquals(404, get(emu, "/computeMetadata/v1/project/numeric-project-id").statusCode())
    }
  }

  @Test
  fun `a disallowed peer is refused before routing`() {
    emulator({ token() }, peerAllowed = { false }).use { emu ->
      assertEquals(403, get(emu, "/").statusCode())
    }
  }

  @Test
  fun `a broker refusal becomes a 500 so the caller's GCP call fails - live revocation`() {
    emulator({ throw WorkerApiException(401, "unauthorized") }).use { emu ->
      val res = get(emu, "/computeMetadata/v1/instance/service-accounts/default/token")
      assertEquals(500, res.statusCode())
    }
  }

  @Test
  fun `project-id derives from a user-managed SA and is null otherwise`() {
    assertEquals("my-project", projectIdFromServiceAccount("x@my-project.iam.gserviceaccount.com"))
    assertNull(projectIdFromServiceAccount("123-compute@developer.gserviceaccount.com"))
    assertNull(projectIdFromServiceAccount("not-an-email"))
  }

  @Test
  fun `run peer check admits private-range sources and rejects public ones`() {
    // A container's own bridge IP, and the host's primary IP after Docker's SNAT, are site-local.
    assertTrue(isTrustedRunPeer(InetAddress.getByName("172.18.0.2")))
    assertTrue(isTrustedRunPeer(InetAddress.getByName("192.168.64.4")))
    assertTrue(isTrustedRunPeer(InetAddress.getByName("10.1.2.3")))
    // A public source (or loopback, which the run path never uses) is not admitted.
    assertFalse(isTrustedRunPeer(InetAddress.getByName("8.8.8.8")))
  }
}

class RefreshingTokenCacheTest {
  @Test
  fun `concurrent cold reads share a single broker claim - single-flight`() {
    val calls = AtomicInteger(0)
    val cache =
        RefreshingTokenCache({
          calls.incrementAndGet()
          Thread.sleep(50) // widen the race window
          token()
        })

    val threads = 16
    val ready = CountDownLatch(threads)
    val go = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(threads)
    repeat(threads) {
      pool.submit {
        ready.countDown()
        go.await()
        cache.current()
      }
    }
    ready.await()
    go.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

    assertEquals(1, calls.get(), "concurrent cold reads must trigger exactly one broker claim")
  }

  @Test
  fun `a token within the refresh skew is re-claimed on the next read`() {
    val calls = AtomicInteger(0)
    // Fixed clock; first token expires 60s out, inside a 120s skew, so the second read re-claims.
    val now = Instant.parse("2026-07-18T00:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    val cache =
        RefreshingTokenCache(
            {
              calls.incrementAndGet()
              token(access = "t${calls.get()}", expiresAt = now.plusSeconds(60))
            },
            refreshSkew = Duration.ofMinutes(2),
            clock = clock,
        )

    assertEquals("t1", cache.current().accessToken)
    assertEquals("t2", cache.current().accessToken)
    assertEquals(2, calls.get())
  }

  @Test
  fun `a fresh token is reused without re-claiming`() {
    val calls = AtomicInteger(0)
    val cache =
        RefreshingTokenCache({
          calls.incrementAndGet()
          token(expiresAt = Instant.now().plusSeconds(3600))
        })
    cache.current()
    cache.current()
    assertEquals(1, calls.get())
  }

  @Test
  fun `forceExpire drops the cache so the next read re-claims`() {
    val calls = AtomicInteger(0)
    val cache =
        RefreshingTokenCache({
          calls.incrementAndGet()
          token(expiresAt = Instant.now().plusSeconds(3600))
        })
    cache.current()
    cache.forceExpire()
    cache.current()
    assertEquals(2, calls.get())
  }

  @Test
  fun `refreshCount advances once per re-claim and stays flat on a cache hit`() {
    val now = Instant.parse("2026-07-18T00:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    // Freshly-minted tokens live an hour — well outside the skew — so only cold claims re-claim.
    val cache =
        RefreshingTokenCache(
            { token(expiresAt = now.plusSeconds(3600)) },
            clock = clock,
        )
    assertEquals(0, cache.refreshCount, "no claim yet")
    cache.current()
    assertEquals(1, cache.refreshCount, "cold claim counts")
    cache.current() // still fresh — a cache hit, not a re-claim
    assertEquals(1, cache.refreshCount, "a cache hit must not advance the counter")
    cache.forceExpire()
    cache.current()
    assertEquals(2, cache.refreshCount, "the post-expiry re-claim counts")
  }

  @Test
  fun `each re-claim emits a structured beacon refresh line with reason and running count`() {
    val logger =
        org.slf4j.LoggerFactory.getLogger(RefreshingTokenCache::class.java)
            as ch.qos.logback.classic.Logger
    val previousLevel = logger.level
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    logger.level = Level.INFO
    logger.addAppender(appender)
    try {
      val now = Instant.parse("2026-07-18T00:00:00Z")
      val clock = Clock.fixed(now, ZoneOffset.UTC)
      // First token expires 60s out, inside the 120s skew, so the second read is a genuine renewal.
      val cache =
          RefreshingTokenCache(
              {
                token(sa = "target@proj.iam.gserviceaccount.com", expiresAt = now.plusSeconds(60))
              },
              refreshSkew = Duration.ofMinutes(2),
              clock = clock,
          )
      cache.current() // cold
      cache.current() // renewal

      val lines =
          appender.list
              .map { it.formattedMessage }
              .filter { it.startsWith("event=beacon.token.refresh") }
      assertEquals(2, lines.size, "expected one line per (re-)claim, got: $lines")
      assertTrue("reason=cold" in lines[0] && "count=1" in lines[0], lines[0])
      assertTrue("reason=renewal" in lines[1] && "count=2" in lines[1], lines[1])
      assertTrue(
          lines.all { "sa=target@proj.iam.gserviceaccount.com" in it && "expires_in=" in it },
          "every line carries the identity and freshness: $lines",
      )
    } finally {
      logger.detachAppender(appender)
      logger.level = previousLevel
    }
  }
}
