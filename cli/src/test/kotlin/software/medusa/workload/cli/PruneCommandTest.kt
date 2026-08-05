package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit coverage for `workload prune`'s df summary formatting — no daemon needed. */
class PruneCommandTest {

  @Test
  fun `formatBytes renders human units`() {
    assertEquals("512 B", formatBytes(512))
    assertEquals("1.0 KB", formatBytes(1024))
    assertEquals("1.5 KB", formatBytes(1536))
    assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
    assertEquals("3.0 GB", formatBytes(3L * 1024 * 1024 * 1024))
  }
}
