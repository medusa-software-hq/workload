package software.medusa.workload.server

import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.iam.credentials.v1.GenerateAccessTokenRequest
import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import com.google.cloud.iam.credentials.v1.ServiceAccountName
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings
import com.google.protobuf.Duration
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val verificationTokenLifetimeSeconds = 60L
private const val cloudPlatformScope = "https://www.googleapis.com/auth/cloud-platform"

/**
 * [detail] is a safe-to-log diagnostic (the underlying exception's class + message — a GCP API
 * error like "PERMISSION_DENIED: ..." or "NOT_FOUND: ..."), never a secret value: this only ever
 * describes *why the IAM/API call failed*, not payload content. Null when [status] is VERIFIED.
 */
data class VerificationResult(val status: VerificationStatus, val detail: String? = null)

/**
 * Checks whether a target service account's owning project has granted the broker's runtime SA
 * impersonation rights (per the M1 opt-in design, 04-impersonation-opt-in.md), and — if
 * [secretEnvVars] references any — whether that impersonated identity can read each referenced
 * secret (per the M2 story, path-a/a1-env-in-revisions.md). Never returns a usable token — any
 * minted token is discarded immediately after use.
 */
interface ImpersonationVerifier {
  suspend fun verify(
      targetServiceAccount: String,
      secretEnvVars: Map<String, String>,
  ): VerificationResult
}

/** Performs a real, minimal-lifetime dry-run mint against GCP IAM, then a dry-run secret read. */
class IamImpersonationVerifier(
    private val iamCredentialsClient: IamCredentialsClient,
) : ImpersonationVerifier {
  override suspend fun verify(
      targetServiceAccount: String,
      secretEnvVars: Map<String, String>,
  ): VerificationResult =
      withContext(Dispatchers.IO) {
        val minted =
            try {
              mintVerificationToken(targetServiceAccount)
            } catch (e: Exception) {
              return@withContext VerificationResult(
                  VerificationStatus.BINDING_MISSING,
                  e.describe(),
              )
            }

        if (secretEnvVars.isEmpty()) {
          return@withContext VerificationResult(VerificationStatus.VERIFIED)
        }

        try {
          checkSecretAccess(minted, secretEnvVars.values)
          VerificationResult(VerificationStatus.VERIFIED)
        } catch (e: Exception) {
          VerificationResult(VerificationStatus.SECRET_INACCESSIBLE, e.describe())
        }
      }

  private fun mintVerificationToken(targetServiceAccount: String) =
      iamCredentialsClient.generateAccessToken(
          GenerateAccessTokenRequest.newBuilder()
              .setName(ServiceAccountName.of("-", targetServiceAccount).toString())
              .addScope(cloudPlatformScope)
              .setLifetime(
                  Duration.newBuilder().setSeconds(verificationTokenLifetimeSeconds).build()
              )
              .build()
      )

  private fun checkSecretAccess(
      minted: com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse,
      secretResourceNames: Collection<String>,
  ) {
    val expireTime = minted.expireTime
    val credentials =
        GoogleCredentials.create(
            AccessToken(
                minted.accessToken,
                Date.from(Instant.ofEpochSecond(expireTime.seconds, expireTime.nanos.toLong())),
            )
        )
    val settings =
        SecretManagerServiceSettings.newBuilder()
            .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
            .build()
    SecretManagerServiceClient.create(settings).use { client ->
      secretResourceNames.forEach { client.accessSecretVersion(it) }
    }
  }
}

private fun Exception.describe(): String = "${this::class.simpleName}: $message"

/** Always reports success — for local dev, where there's no real GCP IAM to check against. */
object AlwaysVerifiedImpersonationVerifier : ImpersonationVerifier {
  override suspend fun verify(
      targetServiceAccount: String,
      secretEnvVars: Map<String, String>,
  ): VerificationResult = VerificationResult(VerificationStatus.VERIFIED)
}
