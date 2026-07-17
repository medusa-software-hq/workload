package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import software.medusa.workload.docker.ProgressDetail
import software.medusa.workload.docker.PullProgress

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
  fun `an auth-flavored pull failure blames the target SA's missing reader grant`() {
    val message =
        pullFailureMessage(
            pinnedRef = "europe-docker.pkg.dev/p/repo/app@sha256:abc",
            serviceAccount = "target@p.iam.gserviceaccount.com",
            reason = "unauthorized: authentication required",
        )
    assertTrue("artifactregistry.reader" in message, message)
    assertTrue("target@p.iam.gserviceaccount.com" in message, message)
  }

  @Test
  fun `an auth failure never suggests falling back to this host's docker login`() {
    // Silent fallback to ambient dev creds would mask a broken opt-in grant: the profile would work
    // on the one laptop that happens to be logged in and fail everywhere else.
    val message =
        pullFailureMessage(
            pinnedRef = "us-docker.pkg.dev/p/repo/app@sha256:abc",
            serviceAccount = "target@p.iam.gserviceaccount.com",
            reason = "denied: permission denied",
        )
    assertFalse("configure-docker" in message, message)
    assertFalse("gcloud auth login" in message, message)
  }

  @Test
  fun `a non-auth pull failure stays terse`() {
    val message =
        pullFailureMessage(
            pinnedRef = "us-docker.pkg.dev/p/repo/app@sha256:abc",
            serviceAccount = "target@p.iam.gserviceaccount.com",
            reason = "manifest unknown",
        )
    assertFalse("artifactregistry.reader" in message, message)
  }

  @Test
  fun `the brokered token is sent to a Google registry as oauth2accesstoken`() {
    val auth = brokeredRegistryAuth("us-docker.pkg.dev/p/repo/app@sha256:abc", "ya29.brokered")

    assertEquals(brokeredRegistryUsername, auth?.username)
    assertEquals("ya29.brokered", auth?.password)
    assertEquals("us-docker.pkg.dev", auth?.serverAddress)
  }

  @Test
  fun `gcr hosts also get the brokered token`() {
    assertEquals("ya29.x", brokeredRegistryAuth("gcr.io/p/app@sha256:abc", "ya29.x")?.password)
    assertEquals("ya29.x", brokeredRegistryAuth("us.gcr.io/p/app@sha256:abc", "ya29.x")?.password)
  }

  @Test
  fun `the brokered token is never sent to a non-Google registry`() {
    // The token is a live credential for the profile's target SA — handing it to a third-party
    // registry would give that host the service account. Anonymous instead; never a leak.
    for (ref in
        listOf(
            "ghcr.io/someone/app@sha256:abc",
            "docker.io/library/busybox@sha256:abc",
            "attacker.example/x@sha256:abc",
            "us-docker.pkg.dev.evil.com/x@sha256:abc",
            "evil-pkg.dev/x@sha256:abc",
            "us-docker.pkg.dev:8080/x@sha256:abc",
        )) {
      assertNull(brokeredRegistryAuth(ref, "ya29.secret"), "must not send the token to $ref")
    }
  }

  @Test
  fun `the CLI's Google-registry rule matches the backend's`() {
    assertTrue(isGoogleRegistryHost("us-docker.pkg.dev"))
    assertTrue(isGoogleRegistryHost("GCR.IO"))
    assertFalse(isGoogleRegistryHost("gcr.io.evil.com"))
    assertFalse(isGoogleRegistryHost("pkg.dev"))
  }

  @Test
  fun `pull progress renders one line per layer state change`() {
    val seen = mutableSetOf<String>()
    val lines =
        listOf(
                PullProgress(status = "Pulling from library/app", id = "latest"),
                PullProgress(
                    status = "Downloading",
                    id = "abc123",
                    progressDetail = ProgressDetail(current = 10, total = 100),
                ),
                // Same layer, same status, more bytes — must not produce a second line.
                PullProgress(
                    status = "Downloading",
                    id = "abc123",
                    progressDetail = ProgressDetail(current = 90, total = 100),
                ),
                PullProgress(status = "Pull complete", id = "abc123"),
                PullProgress(status = "Status: Downloaded newer image for app:latest"),
            )
            .mapNotNull { renderPullProgress(it, seen) }

    assertEquals(
        listOf(
            "latest: Pulling from library/app",
            "abc123: Downloading",
            "abc123: Pull complete",
            "Status: Downloaded newer image for app:latest",
        ),
        lines,
    )
  }

  @Test
  fun `pull progress skips records with no status`() {
    val seen = mutableSetOf<String>()
    assertEquals(null, renderPullProgress(PullProgress(id = "abc123"), seen))
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
