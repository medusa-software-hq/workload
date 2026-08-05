package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.Server
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

/** Counts how often the auth path touches the store, to prove parse-and-drop happens before it. */
private class CountingFleetStore(private val delegate: FleetStore) : FleetStore by delegate {
  val getWorkerCalls = AtomicInteger(0)

  override suspend fun getWorker(workerId: WorkerId): Worker? {
    getWorkerCalls.incrementAndGet()
    return delegate.getWorker(workerId)
  }
}

/**
 * M4-A4: the worker plane is unprobeable — every unauthenticated/garbage request is a bare 404,
 * malformed credentials never reach the store, and a resolver auth failure is a 404 too.
 */
class WorkerPlaneV2UnprobeableTest {

  private lateinit var store: CountingFleetStore
  private lateinit var server: Server
  private lateinit var client: WebClient

  @BeforeTest
  fun start() {
    store = CountingFleetStore(InMemoryFleetStore())
    val nodeVerifier =
        GooglePrincipalVerifier(
            humanAudiences = emptySet(),
            allowedDomain = "medusa.software",
            serviceAudience = "https://api.workload-baseline.medusa.software",
            serviceAccountAllowlist = setOf("gce-node-1@ms-workload.iam.gserviceaccount.com"),
            jwkSource =
                ImmutableJWKSet<SecurityContext>(
                    JWKSet(RSAKeyGenerator(2048).generate().toPublicJWK())
                ),
        )
    server =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            auth = NoOpAuthDecorator,
            fleetStore = store,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
            workerTokenBroker = WorkerTokenBrokerService(store, FakeTokenMinter),
            workerClaimService = WorkerClaimService(store, FakeTokenMinter),
            selfStatusService = SelfStatusService(store),
            v2RegistrationService = RegistrationServiceV2(store),
            workerRunService = WorkerRunService(store),
            workerNodeIdentityService = WorkerNodeIdentityService(nodeVerifier),
        )
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  private fun post(path: String, bearer: String?): AggregatedHttpResponse {
    val headers =
        if (bearer == null) RequestHeaders.of(HttpMethod.POST, path)
        else RequestHeaders.of(HttpMethod.POST, path, "Authorization", "Bearer $bearer")
    return client
        .execute(headers, workerJson.encodeToString(TokenClaimRequest("any")))
        .aggregate()
        .join()
  }

  private fun get(path: String, bearer: String?): AggregatedHttpResponse {
    val headers =
        if (bearer == null) RequestHeaders.of(HttpMethod.GET, path)
        else RequestHeaders.of(HttpMethod.GET, path, "Authorization", "Bearer $bearer")
    return client.execute(headers).aggregate().join()
  }

  @Test
  fun `every v2 endpoint answers an unauthenticated probe with the same bare 404`() {
    val responses =
        listOf(
            post("/worker/v2/registrations", null),
            post("/worker/v2/token", null),
            post("/worker/v2/claim", null),
            post("/worker/v2/runs", null),
            get("/worker/v2/registrations/self", null),
            get("/worker/v2/node/self", null),
        )
    for (response in responses) {
      assertEquals(HttpStatus.NOT_FOUND, response.status())
      assertEquals("", response.contentUtf8())
    }
  }

  @Test
  fun `a garbage credential is a bare 404 on every v2 endpoint`() {
    val garbage = "not.a-real-token"
    for (response in
        listOf(
            post("/worker/v2/token", garbage),
            post("/worker/v2/claim", garbage),
            post("/worker/v2/runs", garbage),
            get("/worker/v2/registrations/self", garbage),
            get("/worker/v2/node/self", garbage),
        )) {
      assertEquals(HttpStatus.NOT_FOUND, response.status())
      assertEquals("", response.contentUtf8())
    }
  }

  @Test
  fun `a JWT-shaped but unverifiable node credential is also a bare 404`() {
    // Three dot-separated segments (passes the cheap shape check) but not a real, signed token —
    // the node verifier's crypto check fails, and that 401 is rewritten to the same bare 404.
    val response =
        get("/worker/v2/node/self", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.bogus-signature")
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertEquals("", response.contentUtf8())
  }

  @Test
  fun `a malformed credential never reaches the store`() {
    // No '.' at all, and a workerId with a non-wlw_ secret: both are parse-and-dropped.
    post("/worker/v2/token", "garbage-no-dot")
    post("/worker/v2/token", "${UUID.randomUUID()}.not-a-wlw-token")
    get("/worker/v2/registrations/self", "${UUID.randomUUID()}.also-bogus")
    assertEquals(0, store.getWorkerCalls.get())
  }

  @Test
  fun `a well-formed but unknown wlw_ credential reaches the store yet still returns 404`() {
    val response =
        post("/worker/v2/token", "${UUID.randomUUID()}.${WorkloadToken.generate(TokenKind.WORKER)}")
    // The format check passed, so the resolver ran (and audit-logged the unknown worker)...
    assertEquals(1, store.getWorkerCalls.get())
    // ...but the 401 it produced is rewritten to the same bare 404 the plane returns for
    // everything.
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertEquals("", response.contentUtf8())
  }
}
