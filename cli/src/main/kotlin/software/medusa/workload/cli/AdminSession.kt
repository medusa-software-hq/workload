package software.medusa.workload.cli

import java.nio.file.Path

/**
 * Raised when there's no usable admin session — the caller turns it into a "run admin login" hint.
 */
class AdminNotLoggedInException(message: String) : Exception(message)

/**
 * The default token refresher for [env]: mints a fresh ID token via that environment's OAuth client
 * (id + the secret this build resolves for it).
 */
internal fun defaultRefresher(env: Environment): (String) -> TokenSet = { refreshToken ->
  val secret =
      env.oauthClientSecret
          ?: throw AdminNotLoggedInException(
              "This CLI build has no admin OAuth client secret for ${env.label}; set " +
                  "${env.oauthClientSecretEnvVar}."
          )
  AdminOAuth(clientId = env.oauthClientId, clientSecret = secret).refresh(refreshToken)
}

/**
 * Supplies a currently-valid Google ID token for admin API calls: hands back the cached one while
 * it's still good, and silently refreshes it (no browser) when it's expired. Only a revoked/expired
 * refresh token forces a fresh `admin login`. Environment-agnostic by construction — the caller
 * passes the environment's [dir] and its [refresher] (see [defaultRefresher]).
 */
class AdminSession(
    private val dir: Path,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val refresher: (String) -> TokenSet,
) {
  fun currentIdToken(): String {
    val credentials =
        loadAdminCredentials(dir)
            ?: throw AdminNotLoggedInException("Not signed in. Run 'workload admin login' first.")

    // Refresh a little early so a token doesn't expire mid-request.
    if (credentials.idTokenExpiresAtEpochSec > nowEpochSec() + 30) {
      return credentials.idToken
    }

    val refreshed =
        try {
          refresher(credentials.refreshToken)
        } catch (e: AdminOAuthException) {
          throw AdminNotLoggedInException(
              "Session expired (${e.code}). Run 'workload admin login' again."
          )
        }

    saveAdminCredentials(
        credentials.copy(
            idToken = refreshed.idToken,
            idTokenExpiresAtEpochSec = refreshed.expiresAtEpochSec,
        ),
        dir,
    )
    return refreshed.idToken
  }
}
