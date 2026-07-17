package software.medusa.workload.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isGoogleRegistryHost] gates who we are willing to send an impersonated access token to, so its
 * near-misses matter more than its happy path. A host that sneaks through here is handed a live
 * credential for the profile's target service account.
 */
class GoogleRegistryHostTest {

  @Test
  fun `artifact registry hosts are accepted`() {
    assertTrue(isGoogleRegistryHost("us-docker.pkg.dev"))
    assertTrue(isGoogleRegistryHost("europe-docker.pkg.dev"))
    assertTrue(isGoogleRegistryHost("us-central1-docker.pkg.dev"))
  }

  @Test
  fun `container registry hosts are accepted`() {
    assertTrue(isGoogleRegistryHost("gcr.io"))
    assertTrue(isGoogleRegistryHost("us.gcr.io"))
    assertTrue(isGoogleRegistryHost("eu.gcr.io"))
  }

  @Test
  fun `matching is case-insensitive`() {
    assertTrue(isGoogleRegistryHost("US-DOCKER.PKG.DEV"))
    assertTrue(isGoogleRegistryHost("GCR.IO"))
  }

  @Test
  fun `third-party registries are rejected`() {
    assertFalse(isGoogleRegistryHost("docker.io"))
    assertFalse(isGoogleRegistryHost("ghcr.io"))
    assertFalse(isGoogleRegistryHost("quay.io"))
    assertFalse(isGoogleRegistryHost("attacker.example"))
  }

  @Test
  fun `lookalike hosts are rejected`() {
    // The whole point: none of these are Google's, and each would otherwise receive a live token.
    assertFalse(isGoogleRegistryHost("evil-pkg.dev"), "no dot before the suffix")
    assertFalse(isGoogleRegistryHost("us-docker.pkg.dev.evil.com"), "suffix must be at the end")
    assertFalse(isGoogleRegistryHost("pkg.dev.evil.com"))
    assertFalse(isGoogleRegistryHost("notgcr.io"))
    assertFalse(isGoogleRegistryHost("gcr.io.evil.com"))
    assertFalse(isGoogleRegistryHost("evilgcr.io"))
  }

  @Test
  fun `a host with a port is rejected rather than port-stripped`() {
    // Google's registries never carry a port; stripping one would let an attacker-run proxy on
    // us-docker.pkg.dev:8080 look legitimate.
    assertFalse(isGoogleRegistryHost("us-docker.pkg.dev:8080"))
    assertFalse(isGoogleRegistryHost("gcr.io:1234"))
  }

  @Test
  fun `the bare suffixes alone are rejected`() {
    assertFalse(isGoogleRegistryHost("pkg.dev"))
    assertFalse(isGoogleRegistryHost(""))
  }
}
