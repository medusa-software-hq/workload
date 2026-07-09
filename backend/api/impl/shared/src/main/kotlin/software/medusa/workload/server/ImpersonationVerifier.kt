package software.medusa.workload.server

import com.google.cloud.iam.credentials.v1.GenerateAccessTokenRequest
import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import com.google.cloud.iam.credentials.v1.ServiceAccountName
import com.google.protobuf.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val verificationTokenLifetimeSeconds = 60L
private const val cloudPlatformScope = "https://www.googleapis.com/auth/cloud-platform"

/**
 * Checks whether a target service account's owning project has granted the broker's runtime SA
 * impersonation rights, per the M1 opt-in design (04-impersonation-opt-in.md). Never returns a
 * usable token — the minted token (if any) is discarded immediately.
 */
interface ImpersonationVerifier {
  suspend fun verify(targetServiceAccount: String): VerificationStatus
}

/** Performs a real, minimal-lifetime dry-run mint against GCP IAM. */
class IamImpersonationVerifier(
    private val iamCredentialsClient: IamCredentialsClient,
) : ImpersonationVerifier {
  override suspend fun verify(targetServiceAccount: String): VerificationStatus =
      withContext(Dispatchers.IO) {
        try {
          val request =
              GenerateAccessTokenRequest.newBuilder()
                  .setName(ServiceAccountName.of("-", targetServiceAccount).toString())
                  .addScope(cloudPlatformScope)
                  .setLifetime(
                      Duration.newBuilder().setSeconds(verificationTokenLifetimeSeconds).build()
                  )
                  .build()
          iamCredentialsClient.generateAccessToken(request)
          VerificationStatus.VERIFIED
        } catch (e: Exception) {
          VerificationStatus.BINDING_MISSING
        }
      }
}

/** Always reports success — for local dev, where there's no real GCP IAM to check against. */
object AlwaysVerifiedImpersonationVerifier : ImpersonationVerifier {
  override suspend fun verify(targetServiceAccount: String): VerificationStatus =
      VerificationStatus.VERIFIED
}
