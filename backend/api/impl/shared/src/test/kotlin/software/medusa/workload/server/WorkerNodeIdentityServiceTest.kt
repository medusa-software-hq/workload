package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.Server
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Exercises the worker plane's GCE-node identity path (M7) end to end over real HTTP: an
 * allow-listed node's ID token is accepted, a non-allow-listed one is rejected the same unprobeable
 * way as the rest of the v2 plane, and none of this disturbs the existing secret-based worker path
 * (covered separately by [WorkerPlaneV2UnprobeableTest]).
 */
class WorkerNodeIdentityServiceTest {
  private val keyId = "test-key"
  private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID(keyId).generate()
  private val jwkSource = ImmutableJWKSet<SecurityContext>(JWKSet(rsaKey.toPublicJWK()))

  private val apiUrl = "https://api.workload-baseline.medusa.software"
  private val allowedNodeSa = "gce-node-1@ms-workload.iam.gserviceaccount.com"

  private lateinit var server: Server
  private lateinit var client: WebClient

  @BeforeTest
  fun start() {
    val verifier =
        GooglePrincipalVerifier(
            humanAudiences = emptySet(),
            allowedDomain = "medusa.software",
            serviceAudience = apiUrl,
            serviceAccountAllowlist = setOf(allowedNodeSa),
            jwkSource = jwkSource,
        )
    server =
        buildWorkerServiceTestServer("/worker/v2/node/self", WorkerNodeIdentityService(verifier))
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  private fun mint(audience: String = apiUrl, email: String? = allowedNodeSa): String {
    val now = System.currentTimeMillis()
    val builder =
        JWTClaimsSet.Builder()
            .subject("1234567890")
            .issuer("https://accounts.google.com")
            .audience(audience)
            .issueTime(Date(now))
            .expirationTime(Date(now + 3600_000))
    if (email != null) builder.claim("email", email)
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), builder.build())
    jwt.sign(RSASSASigner(rsaKey))
    return jwt.serialize()
  }

  private fun getSelf(bearer: String?): AggregatedHttpResponse {
    val headers =
        if (bearer == null) RequestHeaders.of(HttpMethod.GET, "/worker/v2/node/self")
        else
            RequestHeaders.of(
                HttpMethod.GET,
                "/worker/v2/node/self",
                "Authorization",
                "Bearer $bearer",
            )
    return client.execute(headers).aggregate().join()
  }

  @Test
  fun `an allow-listed GCE node's ID token authenticates and gets its service account back`() {
    val response = getSelf(mint())
    assertEquals(HttpStatus.OK, response.status())
    val body = Json.parseToJsonElement(response.contentUtf8()).jsonObject
    assertEquals(allowedNodeSa, body["serviceAccount"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a non-allow-listed GCE node's ID token is rejected`() {
    val response = getSelf(mint(email = "intruder@evil.iam.gserviceaccount.com"))
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `a token minted for a different audience is rejected`() {
    val response = getSelf(mint(audience = "https://api.workload-baseline-staging.medusa.software"))
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `a missing bearer token is rejected`() {
    assertEquals(HttpStatus.UNAUTHORIZED, getSelf(null).status())
  }

  @Test
  fun `a structurally invalid token is rejected`() {
    assertEquals(HttpStatus.UNAUTHORIZED, getSelf("not-a-jwt").status())
  }
}
