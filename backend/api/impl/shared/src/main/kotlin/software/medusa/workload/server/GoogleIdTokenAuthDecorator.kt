package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext

private const val httpAuthorizationHeaderName = "Authorization"
private const val bearerPrefix = "Bearer "

/**
 * Admin-plane auth: verifies the `Authorization: Bearer <token>` Google ID token via
 * [GooglePrincipalVerifier] and, on success, records the verified [Principal] on the request
 * context for audit logging (human vs service). Returns HTTP 401 on any failure — a
 * missing/malformed header, or a token that verifies to no accepted principal.
 *
 * The decorator is a thin adapter; all the trust logic lives in the reusable verifier.
 */
class GoogleIdTokenAuthDecorator(
    private val verifier: GooglePrincipalVerifier,
) : DecoratingHttpServiceFunction {

  companion object {
    private val unauthorized: HttpResponse
      get() = HttpResponse.of(HttpStatus.UNAUTHORIZED)
  }

  override fun serve(
      delegate: HttpService,
      ctx: ServiceRequestContext,
      req: HttpRequest,
  ): HttpResponse {
    val token = extractBearerToken(req) ?: return unauthorized
    val principal = verifier.verify(token) ?: return unauthorized

    ctx.setAttr(adminPrincipalAttrKey, principal)
    return delegate.serve(ctx, req)
  }

  private fun extractBearerToken(req: HttpRequest): String? {
    val header = req.headers().get(httpAuthorizationHeaderName) ?: return null
    if (!header.startsWith(bearerPrefix)) return null
    return header.removePrefix(bearerPrefix).trim().takeIf { it.isNotEmpty() }
  }
}
