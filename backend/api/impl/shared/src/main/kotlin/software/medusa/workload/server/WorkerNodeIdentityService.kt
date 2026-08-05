package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable internal data class NodeSelfResponse(val serviceAccount: String)

/**
 * Implements `GET /worker/v2/node/self` (M7): a GCE VM proves its node identity to the broker by
 * presenting its own service-account ID token — fetched fresh from the metadata server, never
 * stored on disk — instead of a worker's `id.secret` credential. [verifier] is the same
 * [GooglePrincipalVerifier] the admin plane uses, instantiated here with the worker plane's own
 * Terraform-managed allowlist so an admin-allow-listed SA gains no worker-plane power and vice
 * versa.
 *
 * There is no registration/approval ceremony for a node identity, unlike a secret-based worker:
 * allow-listing the SA in Terraform *is* the approval. Never logs the bearer token.
 */
class WorkerNodeIdentityService(
    private val verifier: GooglePrincipalVerifier,
) : HttpService {
  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    val requestId = UUID.randomUUID().toString()
    val sourceIp = ctx.clientAddress().hostAddress

    val token = extractBearerToken(req)
    val principal = token?.let(verifier::verify)
    if (principal == null) {
      audit(
          AuditLogEntry(
              event = "worker_node_identity_denied",
              requestId = requestId,
              timestamp = Instant.now().toString(),
              sourceIp = sourceIp,
              result = "unauthorized",
          )
      )
      return jsonResponse(HttpStatus.UNAUTHORIZED, WorkerErrorResponse("unauthorized"))
    }

    audit(
        AuditLogEntry(
            event = "worker_node_identity_verified",
            requestId = requestId,
            timestamp = Instant.now().toString(),
            sourceIp = sourceIp,
            targetServiceAccount = principal.email,
            result = "success",
        )
    )
    return jsonResponse(HttpStatus.OK, NodeSelfResponse(serviceAccount = principal.email))
  }

  private fun jsonResponse(status: HttpStatus, body: NodeSelfResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))
}
