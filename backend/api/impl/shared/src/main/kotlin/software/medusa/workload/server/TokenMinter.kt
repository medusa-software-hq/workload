package software.medusa.workload.server

import com.google.cloud.iam.credentials.v1.GenerateAccessTokenRequest
import com.google.cloud.iam.credentials.v1.GenerateIdTokenRequest
import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import com.google.cloud.iam.credentials.v1.ServiceAccountName
import com.google.protobuf.Duration
import com.nimbusds.jwt.SignedJWT
import java.time.Instant

private const val cloudPlatformScope = "https://www.googleapis.com/auth/cloud-platform"

data class MintedToken(val accessToken: String, val expiresAt: Instant)

/**
 * A minted OpenID Connect **identity** token: an audience-bound JWT the profile's target service
 * account is the subject of, for authenticating to non-Google services (e.g. Flow's API) which
 * verify it offline against Google's public keys. [expiresAt] is read from the JWT's own `exp`.
 */
data class MintedIdToken(val idToken: String, val expiresAt: Instant)

/**
 * Mints short-lived credentials impersonating a target service account: [mint] for GCP **access**
 * tokens (keys to Google's own APIs), [mintIdToken] for OIDC **identity** tokens bound to a
 * caller-chosen [audience] (identity assertions for everyone else). Access tokens go only to
 * Google; ID tokens go to the service named by the audience.
 */
interface TokenMinter {
  suspend fun mint(targetServiceAccount: String, lifetimeSeconds: Long): MintedToken

  suspend fun mintIdToken(
      targetServiceAccount: String,
      audience: String,
      includeEmail: Boolean,
  ): MintedIdToken
}

/** Mints real tokens via `generateAccessToken`/`generateIdToken`; used in the GCP deployment. */
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

  override suspend fun mintIdToken(
      targetServiceAccount: String,
      audience: String,
      includeEmail: Boolean,
  ): MintedIdToken {
    val name = ServiceAccountName.of("-", targetServiceAccount).toString()
    val request =
        GenerateIdTokenRequest.newBuilder()
            .setName(name)
            .setAudience(audience)
            .setIncludeEmail(includeEmail)
            .build()
    val token = iamCredentialsClient.generateIdToken(request).token
    return MintedIdToken(idToken = token, expiresAt = idTokenExpiry(token))
  }
}

/** Mints fake tokens without contacting GCP; used for local dev. */
object FakeTokenMinter : TokenMinter {
  override suspend fun mint(targetServiceAccount: String, lifetimeSeconds: Long): MintedToken =
      MintedToken(
          accessToken = "fake-access-token",
          expiresAt = Instant.now().plusSeconds(lifetimeSeconds),
      )

  override suspend fun mintIdToken(
      targetServiceAccount: String,
      audience: String,
      includeEmail: Boolean,
  ): MintedIdToken {
    // A well-formed (unsigned) JWT so callers that decode it — including this file's own expiry
    // reader — behave the same as against a real token.
    val expiry = Instant.now().plusSeconds(3600)
    val header = base64Url("""{"alg":"none","typ":"JWT"}""")
    val emailClaim = if (includeEmail) ""","email":"$targetServiceAccount"""" else ""
    val payload =
        base64Url(
            """{"iss":"fake","sub":"$targetServiceAccount","aud":"$audience",""" +
                """"exp":${expiry.epochSecond}$emailClaim}"""
        )
    return MintedIdToken(idToken = "$header.$payload.", expiresAt = expiry)
  }

  private fun base64Url(json: String): String =
      java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}

/** The `exp` claim of an ID token, or a one-hour fallback if it can't be read. */
private fun idTokenExpiry(jwt: String): Instant =
    runCatching { SignedJWT.parse(jwt).jwtClaimsSet.expirationTime?.toInstant() }.getOrNull()
        ?: runCatching {
              // Fake/unsigned tokens don't parse as a SignedJWT; read the payload directly.
              val payload = jwt.split(".")[1]
              val json = String(java.util.Base64.getUrlDecoder().decode(payload))
              val exp = Regex(""""exp"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLong()
              exp?.let { Instant.ofEpochSecond(it) }
            }
            .getOrNull()
        ?: Instant.now().plusSeconds(3600)
