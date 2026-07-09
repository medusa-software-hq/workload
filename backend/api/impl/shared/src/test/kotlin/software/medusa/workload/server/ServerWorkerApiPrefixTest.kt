package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val prefix = "test-prefix-1234"

/** End-to-end check of the UUID-prefix routing built in [buildServer]. */
class ServerWorkerApiPrefixTest {

  private lateinit var server: Server
  private lateinit var client: WebClient
  private var rejectedBefore: Long = 0

  @BeforeTest
  fun start() {
    rejectedBefore = RejectedWorkerRequestCounter.get()
    server =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            workerApiPathPrefix = prefix,
            auth = NoOpAuthDecorator,
            counterStore = InMemoryWorkloadStore(),
            workerTokenBroker =
                HttpService { _, _ ->
                  HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "ok")
                },
        )
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  @Test
  fun `token endpoint under the correct prefix reaches the broker`() {
    val response = client.post("/$prefix/worker/v1/token", "").aggregate().join()
    assertEquals(HttpStatus.OK, response.status())
    assertEquals("ok", response.contentUtf8())
  }

  @Test
  fun `token endpoint under the wrong prefix is a bare 404 and counts as rejected`() {
    val response = client.post("/wrong-prefix/worker/v1/token", "").aggregate().join()
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertTrue(response.contentUtf8().isEmpty())
    assertEquals(rejectedBefore + 1, RejectedWorkerRequestCounter.get())
  }

  @Test
  fun `token endpoint with no prefix at all is a bare 404 and counts as rejected`() {
    val response = client.post("/worker/v1/token", "").aggregate().join()
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertTrue(response.contentUtf8().isEmpty())
    assertEquals(rejectedBefore + 1, RejectedWorkerRequestCounter.get())
  }

  @Test
  fun `health check is unaffected by the prefix`() {
    val response = client.get("/health").aggregate().join()
    assertEquals(HttpStatus.OK, response.status())
  }
}
