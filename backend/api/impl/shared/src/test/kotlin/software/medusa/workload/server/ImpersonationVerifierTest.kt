package software.medusa.workload.server

import com.google.api.gax.rpc.StatusCode
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import java.net.URI
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the token-refresh bug that made every real `secret_env_vars` verification fail with a
 * bogus SECRET_INACCESSIBLE: a short-lived impersonated token, wrapped in the fixed (non-
 * refreshable) [GoogleCredentials], sits inside OAuth2Credentials' ~3-minute expiration margin, so
 * the first API call block-refreshes and throws IllegalStateException. Neither test touches GCP —
 * they exercise the credential's own staleness logic.
 */
class ImpersonationVerifierTest {

  private val secretManagerUri = URI.create("https://secretmanager.googleapis.com/")

  @Test
  fun `impersonatedCredentials do not attempt a refresh when used`() {
    val credentials = impersonatedCredentials("fake-minted-token")

    // getRequestMetadata block-refreshes (and, for a fixed credential, throws) if it considers the
    // token stale. A far-future nominal expiry keeps it fresh, so this must succeed and carry the
    // token through.
    val metadata = credentials.getRequestMetadata(secretManagerUri)

    assertTrue(
        metadata["Authorization"]?.any { it.contains("fake-minted-token") } == true,
        "expected the Authorization header to carry the minted token, got $metadata",
    )
  }

  @Test
  fun `the real token lifetime would trip the refresh path -- documents why the nominal expiry exists`() {
    // A ~60s expiry (the token's actual GCP lifetime) is inside the 3-minute expiration margin, so
    // OAuth2Credentials treats it as expired and block-refreshes; the base fixed credential can't
    // refresh, so it throws. This is exactly what impersonatedCredentials() sidesteps.
    val shortLived =
        GoogleCredentials.create(
            AccessToken("fake-minted-token", Date(System.currentTimeMillis() + 60_000L))
        )

    assertFailsWith<IllegalStateException> { shortLived.getRequestMetadata(secretManagerUri) }
  }

  @Test
  fun `only permission-denied and not-found count as a denial verdict`() {
    // These two mean "confirmed: not authorized / doesn't exist" — safe to record as a
    // BINDING_MISSING / SECRET_INACCESSIBLE verdict.
    assertTrue(isDenialCode(StatusCode.Code.PERMISSION_DENIED))
    assertTrue(isDenialCode(StatusCode.Code.NOT_FOUND))
  }

  @Test
  fun `transient and unexpected statuses are not treated as a denial verdict`() {
    // Recording any of these as a denial is the "catch and pray" bug: they mean "couldn't check",
    // not "the secret is inaccessible", and must leave the revision UNVERIFIED for a retry.
    for (code in
        listOf(
            StatusCode.Code.UNAVAILABLE,
            StatusCode.Code.DEADLINE_EXCEEDED,
            StatusCode.Code.INTERNAL,
            StatusCode.Code.RESOURCE_EXHAUSTED,
            StatusCode.Code.UNAUTHENTICATED,
            StatusCode.Code.ABORTED,
            StatusCode.Code.UNKNOWN,
        )) {
      assertFalse(isDenialCode(code), "$code must not be treated as a denial verdict")
    }
  }
}
