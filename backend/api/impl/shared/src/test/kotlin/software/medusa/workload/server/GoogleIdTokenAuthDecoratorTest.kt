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
 * Exercises the token-verification logic ([GoogleIdTokenAuthDecorator.verify]) against locally
 * signed tokens — a test RSA key stands in for Google's JWKS, so no network and full control over
 * the claims. The important behaviour is the audience allow-list: the SPA client *and* the CLI
 * client are accepted, anything else is not, and none of it relaxes the `hd` domain gate.
 */
class GoogleIdTokenAuthDecoratorTest {
  private val keyId = "test-key"
  private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID(keyId).generate()
  private val jwkSource = ImmutableJWKSet<SecurityContext>(JWKSet(rsaKey.toPublicJWK()))

  private val spaClient = "spa-web-client-id"
  private val cliClient = "cli-desktop-client-id"
  private val domain = "medusa.software"

  private val decorator = GoogleIdTokenAuthDecorator(setOf(spaClient, cliClient), domain, jwkSource)

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

  @Test
  fun `accepts a token from the SPA web client`() {
    assertEquals(
        "admin@medusa.software",
        decorator.verify(mint(audience = spaClient))?.getStringClaim("email"),
    )
  }

  @Test
  fun `accepts a token from the CLI desktop client`() {
    assertEquals(
        "admin@medusa.software",
        decorator.verify(mint(audience = cliClient))?.getStringClaim("email"),
    )
  }

  @Test
  fun `rejects a token whose audience is not allow-listed`() {
    assertNull(decorator.verify(mint(audience = "some-other-client-id")))
  }

  @Test
  fun `rejects a token from the wrong hosted domain`() {
    assertNull(decorator.verify(mint(hd = "evil.example.com")))
  }

  @Test
  fun `rejects a token with no hd claim (a service account token)`() {
    assertNull(decorator.verify(mint(hd = null)))
  }

  @Test
  fun `rejects a token missing the email claim`() {
    assertNull(decorator.verify(mint(email = null)))
  }

  @Test
  fun `rejects a token from an unexpected issuer`() {
    assertNull(decorator.verify(mint(issuer = "https://accounts.evil.example.com")))
  }

  @Test
  fun `rejects an expired token`() {
    // Well past the verifier's default 60s clock-skew tolerance.
    assertNull(decorator.verify(mint(lifetimeSeconds = -3600)))
  }

  @Test
  fun `rejects a token signed by an untrusted key`() {
    assertNull(decorator.verify(mint(signWithTrustedKey = false)))
  }

  @Test
  fun `rejects a structurally invalid token`() {
    assertNull(decorator.verify("not-a-jwt"))
  }
}
