package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
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
 * - `aud` matches [clientId]
 * - Token is not expired
 * - `hd` claim matches [allowedDomain]
 *
 * Returns HTTP 401 on any failure.
 */
class GoogleIdTokenAuthDecorator(
    private val clientId: String,
    private val allowedDomain: String,
) : DecoratingHttpServiceFunction {
  companion object {
    private val unauthorized: HttpResponse
      get() = HttpResponse.of(HttpStatus.UNAUTHORIZED)
  }

  private val jwtProcessor = buildJwtProcessor()

  private fun buildJwtProcessor(): DefaultJWTProcessor<SecurityContext> {
    val jwkSource =
        JWKSourceBuilder.create<SecurityContext>(googleJwksUri).refreshAheadCache(true).build()

    val keySelector = JWSVerificationKeySelector(com.nimbusds.jose.JWSAlgorithm.RS256, jwkSource)

    val claimsVerifier =
        DefaultJWTClaimsVerifier<SecurityContext>(
            com.nimbusds.jwt.JWTClaimsSet.Builder().audience(clientId).build(),
            setOf("sub", "email", "iat", "exp"),
        )

    return DefaultJWTProcessor<SecurityContext>().apply {
      jwsKeySelector = keySelector
      jwtClaimsSetVerifier = claimsVerifier
    }
  }

  override fun serve(
      delegate: HttpService,
      ctx: ServiceRequestContext,
      req: HttpRequest,
  ): HttpResponse {
    val token = extractBearerToken(req) ?: return unauthorized

    val claims =
        try {
          jwtProcessor.process(token, null)
        } catch (_: Exception) {
          return unauthorized
        }

    // Verify issuer manually (nimbus claimsVerifier checks aud/exp/required fields).
    if (claims.issuer !in googleIssuers) return unauthorized

    // Enforce hosted domain.
    val hd = claims.getStringClaim("hd")
    if (hd != allowedDomain) return unauthorized

    ctx.setAttr(adminEmailAttrKey, claims.getStringClaim("email"))
    return delegate.serve(ctx, req)
  }

  private fun extractBearerToken(req: HttpRequest): String? {
    val header = req.headers().get(httpAuthorizationHeaderName) ?: return null
    if (!header.startsWith(bearerPrefix)) return null
    return header.removePrefix(bearerPrefix).trim().takeIf { it.isNotEmpty() }
  }
}
