package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI

private const val httpAuthorizationHeaderName = "Authorization"
private const val bearerPrefix = "Bearer "

private const val googleAccountsHostname = "accounts.google.com"

private val googleJwksUri = URI("https://www.googleapis.com/oauth2/v3/certs").toURL()
private val googleIssuers = setOf("https://$googleAccountsHostname", googleAccountsHostname)

/**
 * Verifies a Google ID token passed as `Authorization: Bearer <token>`.
 *
 * Checks:
 * - Valid signature against Google's JWKS
 * - `iss` is a known Google issuer
 * - `aud` is one of [allowedAudiences]
 * - Token is not expired
 * - `hd` claim matches [allowedDomain]
 *
 * [allowedAudiences] is a *set* because more than one OAuth client legitimately signs humans into
 * this API: the console SPA's Web client and the `workload admin` CLI's Desktop client. A Google ID
 * token's audience is the client it was minted for, so accepting a second client here is what lets
 * a second human front-end in — without opening a service-account path, since SA tokens still carry
 * no `hd` and would fail that check regardless of audience.
 *
 * Returns HTTP 401 on any failure.
 */
class GoogleIdTokenAuthDecorator
internal constructor(
    private val allowedAudiences: Set<String>,
    private val allowedDomain: String,
    jwkSource: JWKSource<SecurityContext>,
) : DecoratingHttpServiceFunction {

  /** Production entry point — verifies against Google's live, cached JWKS. */
  constructor(
      allowedAudiences: Set<String>,
      allowedDomain: String,
  ) : this(
      allowedAudiences,
      allowedDomain,
      JWKSourceBuilder.create<SecurityContext>(googleJwksUri).refreshAheadCache(true).build(),
  )

  companion object {
    private val unauthorized: HttpResponse
      get() = HttpResponse.of(HttpStatus.UNAUTHORIZED)
  }

  private val jwtProcessor = buildJwtProcessor(jwkSource)

  private fun buildJwtProcessor(
      jwkSource: JWKSource<SecurityContext>,
  ): DefaultJWTProcessor<SecurityContext> {
    val keySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)

    // Audience is checked in [verify] against the allow-list, not here — nimbus's single-value
    // audience verifier can't express "any of a set". Everything else (signature, expiry, and the
    // required claims) is enforced here.
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
   * The single verification choke point shared by [serve] and tests: signature, expiry, and the
   * required claims (via the JWT processor), then audience, issuer, and hosted domain. Returns the
   * claims on success, or null on any failure.
   */
  internal fun verify(token: String): JWTClaimsSet? {
    val claims =
        try {
          jwtProcessor.process(token, null)
        } catch (_: Exception) {
          return null
        }

    // Audience must be one of the allow-listed OAuth clients (a token can carry several).
    if ((claims.audience ?: emptyList()).none { it in allowedAudiences }) return null

    // Verify issuer manually (nimbus claimsVerifier checks exp/required fields).
    if (claims.issuer !in googleIssuers) return null

    // Enforce hosted domain.
    if (claims.getStringClaim("hd") != allowedDomain) return null

    return claims
  }

  override fun serve(
      delegate: HttpService,
      ctx: ServiceRequestContext,
      req: HttpRequest,
  ): HttpResponse {
    val token = extractBearerToken(req) ?: return unauthorized
    val claims = verify(token) ?: return unauthorized

    ctx.setAttr(adminEmailAttrKey, claims.getStringClaim("email"))
    return delegate.serve(ctx, req)
  }

  private fun extractBearerToken(req: HttpRequest): String? {
    val header = req.headers().get(httpAuthorizationHeaderName) ?: return null
    if (!header.startsWith(bearerPrefix)) return null
    return header.removePrefix(bearerPrefix).trim().takeIf { it.isNotEmpty() }
  }
}
