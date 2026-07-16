package software.medusa.workload.docker

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** No daemon required: these exercise construction and the connection-failure path. */
class LazyInitAndFailureTest {

  @Test
  fun `constructing a connector starts no transport`() {
    DockerConnector(DockerConnectorConfig("/tmp/unused.sock")).use { connector ->
      assertFalse(
          connector.isTransportInitializedForTest(),
          "no Netty/Armeria should be started just by constructing a connector",
      )
    }
  }

  @Test
  fun `closing an unused connector is a no-op and does not start the transport`() {
    val connector = DockerConnector(DockerConnectorConfig("/tmp/unused.sock"))
    connector.close()
    assertFalse(connector.isTransportInitializedForTest())
  }

  @Test
  fun `a missing socket yields an actionable DockerConnectionException`() = runBlocking {
    val connector = DockerConnector(DockerConnectorConfig("/tmp/definitely-no-docker-here.sock"))
    connector.use {
      val e =
          try {
            it.ping()
            error("expected a DockerConnectionException")
          } catch (ex: DockerConnectionException) {
            ex
          }
      assertTrue(e.message!!.contains("Cannot reach the Docker daemon"))
      assertTrue(e.message!!.contains("/tmp/definitely-no-docker-here.sock"))
    }
  }

  @Test
  fun `the library prints nothing to stderr on a handled connection failure`() = runBlocking {
    val originalErr = System.err
    val captured = ByteArrayOutputStream()
    System.setErr(PrintStream(captured, true, "UTF-8"))
    try {
      DockerConnector(DockerConnectorConfig("/tmp/definitely-no-docker-here.sock")).use {
        runCatching { it.ping() } // handled: we expect the typed exception, no printing
      }
      // Give any (mis)behaving event-loop thread a beat to have flushed output, if it were going
      // to.
      Thread.sleep(200)
    } finally {
      System.setErr(originalErr)
    }
    assertEquals(
        "",
        captured.toString("UTF-8").trim(),
        "the connector must not print — including from Netty/Armeria event-loop threads",
    )
  }
}
