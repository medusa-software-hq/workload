package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiVersionTest {

  @Test
  fun `negotiate uses the server version when between floor and ceiling`() {
    assertEquals("1.45", ApiVersion.negotiate("1.45"))
  }

  @Test
  fun `negotiate caps at the ceiling when the server is newer`() {
    // VM daemon advertises 1.55; we cap at what we've validated.
    assertEquals(ApiVersion.MAX_SUPPORTED, ApiVersion.negotiate("1.55"))
  }

  @Test
  fun `negotiate accepts a server exactly at the floor`() {
    assertEquals(ApiVersion.MIN_SUPPORTED, ApiVersion.negotiate(ApiVersion.MIN_SUPPORTED))
  }

  @Test
  fun `negotiate rejects a server below the floor`() {
    val e = assertFailsWith<DockerProtocolException> { ApiVersion.negotiate("1.40") }
    assertTrue(e.message!!.contains("older than the minimum"))
  }

  @Test
  fun `compare orders by major then minor`() {
    assertTrue(ApiVersion.compare("1.41", "1.45") < 0)
    assertTrue(ApiVersion.compare("2.0", "1.99") > 0)
    assertEquals(0, ApiVersion.compare("1.51", "1.51"))
  }

  @Test
  fun `an unparseable version is a protocol error`() {
    assertFailsWith<DockerProtocolException> { ApiVersion.negotiate("not-a-version") }
  }
}
