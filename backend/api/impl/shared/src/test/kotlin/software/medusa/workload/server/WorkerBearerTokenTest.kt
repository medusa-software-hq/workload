package software.medusa.workload.server

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkerBearerTokenTest {

  @Test
  fun `parses a well-formed workerId dot secret token`() {
    val workerId = UUID.randomUUID()
    val parsed = parseWorkerBearerToken("$workerId.my-secret-value")
    assertEquals(WorkerId(workerId) to "my-secret-value", parsed)
  }

  @Test
  fun `secret may itself contain dots (takes everything after the first one)`() {
    val workerId = UUID.randomUUID()
    val parsed = parseWorkerBearerToken("$workerId.part1.part2")
    assertEquals(WorkerId(workerId) to "part1.part2", parsed)
  }

  @Test
  fun `returns null when there is no separator`() {
    assertNull(parseWorkerBearerToken("not-a-valid-token"))
  }

  @Test
  fun `returns null when the worker id is not a valid UUID`() {
    assertNull(parseWorkerBearerToken("not-a-uuid.some-secret"))
  }

  @Test
  fun `returns null when the secret half is empty`() {
    assertNull(parseWorkerBearerToken("${UUID.randomUUID()}."))
  }
}
