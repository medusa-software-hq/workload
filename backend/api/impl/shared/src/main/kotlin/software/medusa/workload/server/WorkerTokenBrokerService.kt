package software.medusa.workload.server

import com.google.cloud.iam.credentials.v1.GenerateAccessTokenRequest
import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import com.google.cloud.iam.credentials.v1.ServiceAccountName
import com.google.protobuf.Duration
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private const val httpAuthorizationHeaderName = "Authorization"
private const val bearerPrefix = "Bearer "
private const val cloudPlatformScope = "https://www.googleapis.com/auth/cloud-platform"

@Serializable
internal data class WorkerTokenResponse(
    val accessToken: String,
    val expiresAt: String,
    val serviceAccount: String,
)

@Serializable internal data class WorkerTokenErrorResponse(val error: String)

@Serializable
private data class AuditLogEntry(
    val event: String,
    val requestId: String,
    val timestamp: String,
    val sourceIp: String,
    val targetServiceAccount: String,
    val result: String,
    val expiresAt: String? = null,
    val reason: String? = null,
)

private val json = Json { encodeDefaults = true }
private val auditLogger = LoggerFactory.getLogger("worker.token.broker.audit")

internal fun extractBearerToken(req: HttpRequest): String? {
  val header = req.headers().get(httpAuthorizationHeaderName) ?: return null
  if (!header.startsWith(bearerPrefix)) return null
  return header.removePrefix(bearerPrefix).trim().takeIf { it.isNotEmpty() }
}

internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

internal fun constantTimeEquals(a: String, b: String): Boolean =
    constantTimeEquals(a.toByteArray(), b.toByteArray())

/**
 * Implements `POST /worker/v1/token`: exchanges [WorkerTokenBrokerConfig.bootstrapToken] for a
 * short-lived GCP access token impersonating [WorkerTokenBrokerConfig.targetServiceAccountEmail].
 *
 * Never logs the bootstrap token, the Authorization header, or the minted access token. Every
 * request (success or failure) is audit-logged as a single structured JSON line.
 */
class WorkerTokenBrokerService(
    private val config: WorkerTokenBrokerConfig,
    private val iamCredentialsClient: IamCredentialsClient,
) : HttpService {

  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    val requestId = UUID.randomUUID().toString()
    val sourceIp = ctx.clientAddress().hostAddress

    val token = extractBearerToken(req)
    if (token == null || !constantTimeEquals(token, config.bootstrapToken)) {
      audit(requestId, sourceIp, result = "unauthorized", reason = "invalid_or_missing_token")
      return jsonResponse(HttpStatus.UNAUTHORIZED, WorkerTokenErrorResponse("unauthorized"))
    }

    // generateAccessToken is a blocking call; keep it off Armeria's event-loop thread.
    return HttpResponse.of(
        {
          try {
            val (accessToken, expiresAt) = mintAccessToken()
            audit(requestId, sourceIp, result = "success", expiresAt = expiresAt.toString())
            jsonResponse(
                HttpStatus.OK,
                WorkerTokenResponse(
                    accessToken,
                    expiresAt.toString(),
                    config.targetServiceAccountEmail,
                ),
            )
          } catch (e: Exception) {
            audit(requestId, sourceIp, result = "denied", reason = e::class.simpleName)
            jsonResponse(HttpStatus.BAD_GATEWAY, WorkerTokenErrorResponse("failed_to_mint_token"))
          }
        },
        ctx.blockingTaskExecutor(),
    )
  }

  private fun mintAccessToken(): Pair<String, Instant> {
    val name = ServiceAccountName.of("-", config.targetServiceAccountEmail).toString()
    val request =
        GenerateAccessTokenRequest.newBuilder()
            .setName(name)
            .addScope(cloudPlatformScope)
            .setLifetime(Duration.newBuilder().setSeconds(config.tokenLifetimeSeconds).build())
            .build()
    val response = iamCredentialsClient.generateAccessToken(request)
    val expireTime = response.expireTime
    return response.accessToken to
        Instant.ofEpochSecond(expireTime.seconds, expireTime.nanos.toLong())
  }

  private fun jsonResponse(status: HttpStatus, body: WorkerTokenResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, json.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerTokenErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, json.encodeToString(body))

  private fun audit(
      requestId: String,
      sourceIp: String,
      result: String,
      expiresAt: String? = null,
      reason: String? = null,
  ) {
    val entry =
        AuditLogEntry(
            event = "worker_gcp_token_${if (result == "success") "issued" else "denied"}",
            requestId = requestId,
            timestamp = Instant.now().toString(),
            sourceIp = sourceIp,
            targetServiceAccount = config.targetServiceAccountEmail,
            result = result,
            expiresAt = expiresAt,
            reason = reason,
        )
    auditLogger.info(json.encodeToString(entry))
  }
}
