package software.medusa.workload.server

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises [GooglePrincipalVerifier] against locally signed tokens — a test RSA key stands in for
 * Google's JWKS, so no network and full control over the claims. Covers both principal kinds:
 * humans (SPA/CLI OAuth client audience + `hd`) and service accounts (this API's URL as the
 * audience + an allow-listed email), and the ways each is rejected — including the
 * cross-environment case (a token minted for a different API URL, or from an un-listed SA).
 */
class GooglePrincipalVerifierTest {
  private val keyId = "test-key"
  private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID(keyId).generate()
  private val jwkSource = ImmutableJWKSet<SecurityContext>(JWKSet(rsaKey.toPublicJWK()))

  private val spaClient = "spa-web-client-id"
  private val cliClient = "cli-desktop-client-id"
  private val domain = "medusa.software"
  private val apiUrl = "https://api.workload-baseline.medusa.software"
  private val ciSa = "workload-ci-admin@ms-workload.iam.gserviceaccount.com"

  private val verifier =
      GooglePrincipalVerifier(
          humanAudiences = setOf(spaClient, cliClient),
          allowedDomain = domain,
          serviceAudience = apiUrl,
          serviceAccountAllowlist = setOf(ciSa),
          jwkSource = jwkSource,
      )

  private fun mint(
      audience: String = spaClient,
      hd: String? = domain,
      email: String? = "admin@medusa.software",
      issuer: String = "https://accounts.google.com",
      // Signed with a valid key by default; set false to simulate a token from another issuer's
      // key.
      signWithTrustedKey: Boolean = true,
      lifetimeSeconds: Long = 3600,
  ): String {
    val now = System.currentTimeMillis()
    val builder =
        JWTClaimsSet.Builder()
            .subject("1234567890")
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date(now))
            .expirationTime(Date(now + lifetimeSeconds * 1000))
    if (hd != null) builder.claim("hd", hd)
    if (email != null) builder.claim("email", email)

    val signingKey = if (signWithTrustedKey) rsaKey else RSAKeyGenerator(2048).generate()
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), builder.build())
    jwt.sign(RSASSASigner(signingKey))
    return jwt.serialize()
  }

  // region Human principals

  @Test
  fun `accepts a human token from the SPA web client`() {
    assertEquals(
        Principal.Human("admin@medusa.software"),
        verifier.verify(mint(audience = spaClient)),
    )
  }

  @Test
  fun `accepts a human token from the CLI desktop client`() {
    assertEquals(
        Principal.Human("admin@medusa.software"),
        verifier.verify(mint(audience = cliClient)),
    )
  }

  @Test
  fun `rejects a human token whose audience is not allow-listed`() {
    assertNull(verifier.verify(mint(audience = "some-other-client-id")))
  }

  @Test
  fun `rejects a human token from the wrong hosted domain`() {
    assertNull(verifier.verify(mint(hd = "evil.example.com")))
  }

  // endregion

  // region Service principals

  @Test
  fun `accepts an allow-listed SA token minted for this API's URL`() {
    assertEquals(
        Principal.Service(ciSa),
        verifier.verify(mint(audience = apiUrl, hd = null, email = ciSa)),
    )
  }

  @Test
  fun `rejects an SA token from an SA not on the allowlist`() {
    assertNull(
        verifier.verify(
            mint(audience = apiUrl, hd = null, email = "intruder@evil.iam.gserviceaccount.com")
        )
    )
  }

  @Test
  fun `rejects an allow-listed SA token minted for a different audience`() {
    // A staging-minted SA token names the staging API URL; against prod's verifier that is not the
    // service audience, so it is refused.
    assertNull(
        verifier.verify(
            mint(
                audience = "https://api.workload-baseline-staging.medusa.software",
                hd = null,
                email = ciSa,
            )
        )
    )
  }

  @Test
  fun `rejects a service-audience token whose email is not allow-listed`() {
    assertNull(
        verifier.verify(mint(audience = apiUrl, hd = null, email = "nobody@medusa.software"))
    )
  }

  // endregion

  // region Base checks (apply to both kinds)

  @Test
  fun `rejects a token missing the email claim`() {
    assertNull(verifier.verify(mint(email = null)))
  }

  @Test
  fun `rejects a token from an unexpected issuer`() {
    assertNull(verifier.verify(mint(issuer = "https://accounts.evil.example.com")))
  }

  @Test
  fun `rejects an expired token`() {
    // Well past the verifier's default 60s clock-skew tolerance.
    assertNull(verifier.verify(mint(lifetimeSeconds = -3600)))
  }

  @Test
  fun `rejects a token signed by an untrusted key`() {
    assertNull(verifier.verify(mint(signWithTrustedKey = false)))
  }

  @Test
  fun `rejects a structurally invalid token`() {
    assertNull(verifier.verify("not-a-jwt"))
  }

  // endregion
}
