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
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private const val verificationTokenLifetimeSeconds = 60L
private const val cloudPlatformScope = "https://www.googleapis.com/auth/cloud-platform"

// One-hour nominal expiry we hand the client library for the impersonated token — deliberately NOT
// its real ~60s GCP lifetime. OAuth2Credentials does a *blocking* refresh once a token is within
// its expiration margin (3 minutes by default), but GoogleCredentials.create(AccessToken) is a
// fixed, non-refreshable credential whose refreshAccessToken() throws. A genuinely 60s token is
// always inside that 3-minute margin, so the very first Secret Manager call would attempt a
// refresh and throw IllegalStateException — which we'd misreport as SECRET_INACCESSIBLE. We make a
// single synchronous call and close the client immediately, so the token is used well within its
// real 60s validity and this fictional expiry is never observed to be wrong.
private const val credentialNominalLifetimeMillis = 60L * 60L * 1000L

private val logger = LoggerFactory.getLogger(IamImpersonationVerifier::class.java)

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
              logger.warn("Impersonation dry-run mint failed for {}", targetServiceAccount, e)
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
          logger.warn(
              "Secret-access dry-run failed for {} (env vars {}): {}",
              targetServiceAccount,
              secretEnvVars.keys,
              e.toString(),
              e,
          )
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
    val settings =
        SecretManagerServiceSettings.newBuilder()
            .setCredentialsProvider(
                FixedCredentialsProvider.create(impersonatedCredentials(minted.accessToken))
            )
            .build()
    SecretManagerServiceClient.create(settings).use { client ->
      secretResourceNames.forEach { client.accessSecretVersion(it) }
    }
  }
}

/**
 * Wraps a minted, short-lived impersonated [accessToken] in fixed credentials with a far-future
 * *nominal* expiry — see [credentialNominalLifetimeMillis] for why the reported expiry must not be
 * the token's real ~60s lifetime. Extracted so a test can assert the credential doesn't trip
 * OAuth2Credentials' refresh path, which is what this whole dance exists to avoid.
 */
internal fun impersonatedCredentials(accessToken: String): GoogleCredentials =
    GoogleCredentials.create(
        AccessToken(accessToken, Date(System.currentTimeMillis() + credentialNominalLifetimeMillis))
    )

private fun Exception.describe(): String = "${this::class.simpleName}: $message"

/** Always reports success — for local dev, where there's no real GCP IAM to check against. */
object AlwaysVerifiedImpersonationVerifier : ImpersonationVerifier {
  override suspend fun verify(
      targetServiceAccount: String,
      secretEnvVars: Map<String, String>,
  ): VerificationResult = VerificationResult(VerificationStatus.VERIFIED)
}
