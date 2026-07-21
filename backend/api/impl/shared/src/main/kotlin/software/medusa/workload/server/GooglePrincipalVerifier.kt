package software.medusa.workload.server

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI

private const val googleAccountsHostname = "accounts.google.com"

private val googleJwksUri = URI("https://www.googleapis.com/oauth2/v3/certs").toURL()
private val googleIssuers = setOf("https://$googleAccountsHostname", googleAccountsHostname)

/**
 * Verifies a Google-signed ID token and classifies its caller as a [Principal] — the reusable core
 * shared by the admin-plane auth decorator and (M7) the worker-plane node principal. Not tied to
 * the admin plane: it only knows Google tokens and the two ways a caller is trusted.
 *
 * Every token must pass the same base checks — valid signature against Google's JWKS, a known
 * Google issuer, not expired, and the required claims present. Then it is classified:
 *
 * - **[Principal.Human]**: `hd` = [allowedDomain] AND `aud` ∈ [humanAudiences] (the OAuth clients
 *   humans sign in with — the SPA Web client and the CLI Desktop client). A token can carry several
 *   audiences; any allow-listed one matches.
 * - **[Principal.Service]**: no `hd` (service-account tokens carry none), `aud` = [serviceAudience]
 *   (this API's own URL — what a WIF/ADC-minted ID token names), AND `email` ∈
 *   [serviceAccountAllowlist]. The audience *and* the allowlist must both match, so a token minted
 *   for a different environment's API URL, or from an un-listed SA, is rejected.
 *
 * Anything else → `null`. The order matters only in that a real human token (which has `hd`) can
 * never satisfy the service branch and vice versa, so the two are disjoint.
 */
class GooglePrincipalVerifier
internal constructor(
    private val humanAudiences: Set<String>,
    private val allowedDomain: String,
    private val serviceAudience: String?,
    private val serviceAccountAllowlist: Set<String>,
    jwkSource: JWKSource<SecurityContext>,
) {

  /** Production entry point — verifies against Google's live, cached JWKS. */
  constructor(
      humanAudiences: Set<String>,
      allowedDomain: String,
      serviceAudience: String?,
      serviceAccountAllowlist: Set<String>,
  ) : this(
      humanAudiences,
      allowedDomain,
      serviceAudience,
      serviceAccountAllowlist,
      JWKSourceBuilder.create<SecurityContext>(googleJwksUri).refreshAheadCache(true).build(),
  )

  private val jwtProcessor = buildJwtProcessor(jwkSource)

  private fun buildJwtProcessor(
      jwkSource: JWKSource<SecurityContext>,
  ): DefaultJWTProcessor<SecurityContext> {
    val keySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)

    // Audience is checked in [verify] against the allow-lists, not here — nimbus's single-value
    // audience verifier can't express "any of a set", and the accepted audience differs by
    // principal
    // kind. Everything else (signature, expiry, and the required claims) is enforced here.
    val claimsVerifier =
        DefaultJWTClaimsVerifier<SecurityContext>(
            JWTClaimsSet.Builder().build(),
            setOf("sub", "email", "iat", "exp"),
        )

    return DefaultJWTProcessor<SecurityContext>().apply {
      jwsKeySelector = keySelector
      jwtClaimsSetVerifier = claimsVerifier
    }
  }

  /**
   * The single verification choke point: signature, expiry, and required claims (via the JWT
   * processor), then issuer and the per-kind classification. Returns the verified [Principal], or
   * null on any failure.
   */
  fun verify(token: String): Principal? {
    val claims =
        try {
          jwtProcessor.process(token, null)
        } catch (_: Exception) {
          return null
        }

    if (claims.issuer !in googleIssuers) return null
    val email = claims.getStringClaim("email") ?: return null
    val audiences = claims.audience ?: emptyList()

    // Human: Workspace domain + a human OAuth client audience.
    if (claims.getStringClaim("hd") == allowedDomain && audiences.any { it in humanAudiences }) {
      return Principal.Human(email)
    }

    // Service: audience is this API's own URL and the SA is on the allowlist. SA tokens carry no
    // `hd`, so the two branches never overlap.
    if (
        serviceAudience != null &&
            audiences.contains(serviceAudience) &&
            email in serviceAccountAllowlist
    ) {
      return Principal.Service(email)
    }

    return null
  }
}
