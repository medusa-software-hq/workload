package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure unit tests for `workload run`'s ref/env/error logic — no daemon needed. */
class RunCommandTest {

  @Test
  fun `pins a tag ref to the digest the revision resolved`() {
    val pinned =
        pinnedImageRef(ClaimImage(ref = "us-docker.pkg.dev/p/repo/app:v1", digest = "sha256:abc"))
    assertEquals("us-docker.pkg.dev/p/repo/app@sha256:abc", pinned)
  }

  @Test
  fun `pinning a ref with no tag still addresses it by digest`() {
    val pinned =
        pinnedImageRef(ClaimImage(ref = "us-docker.pkg.dev/p/repo/app", digest = "sha256:abc"))
    assertEquals("us-docker.pkg.dev/p/repo/app@sha256:abc", pinned)
  }

  @Test
  fun `a ref that already carries a digest is re-pinned to the claim's digest`() {
    // The revision's digest is the source of truth, not whatever the ref happened to embed.
    val pinned =
        pinnedImageRef(
            ClaimImage(ref = "us-docker.pkg.dev/p/repo/app@sha256:stale", digest = "sha256:fresh")
        )
    assertEquals("us-docker.pkg.dev/p/repo/app@sha256:fresh", pinned)
  }

  @Test
  fun `a registry port is not mistaken for a tag separator`() {
    assertEquals("localhost:5000/app", repositoryOf("localhost:5000/app"))
    assertEquals("localhost:5000/app", repositoryOf("localhost:5000/app:v2"))
  }

  @Test
  fun `registry host is the first path segment`() {
    assertEquals("us-docker.pkg.dev", registryHostOf("us-docker.pkg.dev/p/repo/app@sha256:abc"))
  }

  @Test
  fun `container env carries profile vars, secrets, and the token under both names`() {
    val env =
        buildContainerEnv(
            profileEnv = mapOf("MODE" to "batch", "API_KEY" to "resolved-secret"),
            accessToken = "abc123",
        )

    assertTrue("MODE=batch" in env)
    assertTrue("API_KEY=resolved-secret" in env)
    assertTrue("$googleOauthAccessTokenEnvVar=abc123" in env)
    assertTrue("$cloudsdkAuthAccessTokenEnvVar=abc123" in env)
  }

  @Test
  fun `container env does not inherit the host environment`() {
    // A container starts from its image's env; the operator's shell must not leak in.
    val env = buildContainerEnv(profileEnv = emptyMap(), accessToken = "t")
    val names = env.map { it.substringBefore('=') }.toSet()
    assertEquals(setOf(googleOauthAccessTokenEnvVar, cloudsdkAuthAccessTokenEnvVar), names)
  }

  @Test
  fun `auth-flavored pull failures are recognized`() {
    assertTrue(
        looksLikeAuthFailure("Error response from daemon: unauthorized: authentication required")
    )
    assertTrue(
        looksLikeAuthFailure(
            "denied: Permission \"artifactregistry.repositories.downloadArtifacts\" denied"
        )
    )
    assertTrue(looksLikeAuthFailure("no basic auth credentials"))
  }

  @Test
  fun `non-auth pull failures are not mistaken for auth ones`() {
    assertFalse(looksLikeAuthFailure("manifest for us-docker.pkg.dev/p/repo/app:v9 not found"))
    assertFalse(looksLikeAuthFailure("dial tcp: lookup registry: no such host"))
  }

  @Test
  fun `an auth-flavored pull failure names the one-time gcloud setup for that registry`() {
    val message =
        pullFailureMessage(
            pinnedRef = "europe-docker.pkg.dev/p/repo/app@sha256:abc",
            exitCode = 1,
            output = "Error response from daemon: unauthorized: authentication required",
        )
    assertTrue("gcloud auth configure-docker europe-docker.pkg.dev" in message, message)
  }

  @Test
  fun `a non-auth pull failure does not suggest the gcloud setup`() {
    val message =
        pullFailureMessage(
            pinnedRef = "us-docker.pkg.dev/p/repo/app@sha256:abc",
            exitCode = 1,
            output = "manifest unknown",
        )
    assertFalse("configure-docker" in message, message)
  }

  @Test
  fun `an unresolvable image claim error points at the artifactregistry reader grant`() {
    val message =
        tokenClaimErrorMessage(
            WorkerApiException(403, "image_unresolvable"),
            profileId = "my-profile-1",
        )
    assertTrue("artifactregistry.reader" in message, message)
  }
}
