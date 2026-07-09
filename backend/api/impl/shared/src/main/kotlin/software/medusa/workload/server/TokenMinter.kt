package software.medusa.workload.server

import com.google.cloud.iam.credentials.v1.GenerateAccessTokenRequest
import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import com.google.cloud.iam.credentials.v1.ServiceAccountName
import com.google.protobuf.Duration
import java.time.Instant

private const val cloudPlatformScope = "https://www.googleapis.com/auth/cloud-platform"

data class MintedToken(val accessToken: String, val expiresAt: Instant)

/** Mints short-lived GCP access tokens impersonating a target service account. */
interface TokenMinter {
  suspend fun mint(targetServiceAccount: String, lifetimeSeconds: Long): MintedToken
}

/** Mints real access tokens via `generateAccessToken`; used in the GCP deployment. */
class IamTokenMinter(private val iamCredentialsClient: IamCredentialsClient) : TokenMinter {
  override suspend fun mint(targetServiceAccount: String, lifetimeSeconds: Long): MintedToken {
    val name = ServiceAccountName.of("-", targetServiceAccount).toString()
    val request =
        GenerateAccessTokenRequest.newBuilder()
            .setName(name)
            .addScope(cloudPlatformScope)
            .setLifetime(Duration.newBuilder().setSeconds(lifetimeSeconds).build())
            .build()
    val response = iamCredentialsClient.generateAccessToken(request)
    val expireTime = response.expireTime
    return MintedToken(
        accessToken = response.accessToken,
        expiresAt = Instant.ofEpochSecond(expireTime.seconds, expireTime.nanos.toLong()),
    )
  }
}

/** Mints fake tokens without contacting GCP; used for local dev. */
object FakeTokenMinter : TokenMinter {
  override suspend fun mint(targetServiceAccount: String, lifetimeSeconds: Long): MintedToken =
      MintedToken(
          accessToken = "fake-access-token",
          expiresAt = Instant.now().plusSeconds(lifetimeSeconds),
      )
}
