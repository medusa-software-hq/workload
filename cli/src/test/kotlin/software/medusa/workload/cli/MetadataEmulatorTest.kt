package software.medusa.workload.cli

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
): MetadataEmulator =
    MetadataEmulator(
            RefreshingTokenCache(claimer, refreshSkew, clock),
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            peerAllowed,
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
  fun `identity endpoint is a deliberate 404 until ID-token brokering lands`() {
    emulator({ token() }).use { emu ->
      assertEquals(
          404,
          get(emu, "/computeMetadata/v1/instance/service-accounts/default/identity").statusCode(),
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
}
