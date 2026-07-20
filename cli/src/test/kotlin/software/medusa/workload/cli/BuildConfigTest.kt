package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildConfigTest {
  @Test
  fun `with nothing baked or overridden, the API base URL is the prod default`() {
    // The test classpath has no baked workload-admin-build.properties, and CI/dev shells don't set
    // WORKLOAD_API_URL — so this exercises the fallback leg.
    if (
        System.getenv(BuildConfig.API_URL_ENV).isNullOrBlank() &&
            System.getenv("WORKLOAD_ADMIN_API_URL").isNullOrBlank() &&
            BuildConfig.bakedProperty("apiBaseUrl") == null
    ) {
      assertEquals(BuildConfig.DEFAULT_API_BASE_URL, BuildConfig.apiBaseUrl)
    }
  }

  @Test
  fun `a missing baked property reads as null, not blank`() {
    assertEquals(null, BuildConfig.bakedProperty("definitely-not-a-real-key"))
  }
}
