package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildConfigTest {
  @Test
  fun `a missing baked property reads as null, not blank`() {
    assertEquals(null, BuildConfig.bakedProperty("definitely-not-a-real-key"))
  }
}
