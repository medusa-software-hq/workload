package software.medusa.workload.cli

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import java.io.IOException

/**
 * A token provider for the admin plane backed by **ambient** Google credentials — Application
 * Default Credentials on a developer box, or a Workload Identity Federation credential in CI. It
 * mints a Google ID token whose audience is the backend's own URL, exactly the shape the server's
 * `GooglePrincipalVerifier` accepts as a service principal (audience = API URL, issuer Google,
 * email ∈ the allowlist). No browser, no cached refresh token.
 *
 * Returns null — rather than throwing — when this environment has **no** credential that can mint
 * an ID token, so `--auth=auto` can quietly fall back to the human sign-in. That is the common dev
 * case: `gcloud auth application-default login` yields user credentials, which are not an
 * [IdTokenProvider] (only service accounts, impersonated SAs, and WIF external accounts are), so
 * auto correctly prefers the browser flow there. A present-but-broken credential (e.g. missing
 * token-creator IAM) still surfaces its failure — lazily, when the token is first minted on a real
 * call.
 *
 * [loadCredentials] is injectable so the resolution logic is unit-testable without real ADC.
 */
internal fun serviceIdTokenProvider(
    audience: String,
    loadCredentials: () -> GoogleCredentials = { GoogleCredentials.getApplicationDefault() },
): (() -> String)? {
  val credentials =
      try {
        loadCredentials()
      } catch (e: IOException) {
        // No ADC discoverable on this machine — not an error, just "no service credential here".
        return null
      }
  if (credentials !is IdTokenProvider) return null

  val idTokenCredentials =
      IdTokenCredentials.newBuilder()
          .setIdTokenProvider(credentials)
          .setTargetAudience(audience)
          .build()

  return {
    idTokenCredentials.refreshIfExpired()
    idTokenCredentials.idToken.tokenValue
  }
}
