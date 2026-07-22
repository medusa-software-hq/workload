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
 * Returns null — rather than throwing — when this environment has **no** ADC at all, or an ADC that
 * cannot mint an ID token, so the caller can report a clean "no service credentials" error under
 * `sa`. Note that `gcloud auth application-default login` user credentials **can** mint ID tokens
 * in this `google-auth` version (they *are* an [IdTokenProvider]) — so a developer's ADC would
 * produce a token whose identity is their human account, which the admin plane's service-account
 * allowlist rejects. That is exactly why auth is chosen explicitly ([AdminAuthMethod]) and defaults
 * to human sign-in, rather than auto-detected from whatever ADC happens to be present. A
 * present-but-broken credential (e.g. missing token-creator IAM) still surfaces its failure lazily,
 * when the token is first minted on a real call.
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
