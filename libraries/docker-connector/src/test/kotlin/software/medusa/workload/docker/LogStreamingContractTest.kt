package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Contract tests for M3-03 log streaming against a **real** Docker daemon. Same self-skip-locally /
 * required-in-CI policy as the other contract suites.
 *
 * Covers: stdout/stderr routing, follow-mode termination on exit, per-stream ordering under heavy
 * interleaved output (Docker only guarantees per-stream order, so that's what we assert), TTY raw
 * mode, and that cancelling a follow leaks no threads/sockets.
 */
class LogStreamingContractTest {

  private val runToken = "logtest-" + java.util.UUID.randomUUID().toString().take(8)
  private val ownerLabel = "ms-workload.test-owner"

  private fun withConnector(block: suspend (DockerConnector) -> Unit) {
    val config = DockerConnectorConfig.fromEnvironment()
    DockerConnector(config).use { connector ->
      val reachable =
          try {
            runBlocking { connector.ping() }
            true
          } catch (e: DockerConnectionException) {
            false
          }
      if (!reachable) {
        if (!System.getenv("DOCKER_CONTRACT_REQUIRED").isNullOrBlank()) {
          fail(
              "DOCKER_CONTRACT_REQUIRED is set but no Docker daemon was reachable at " +
                  "${config.socketPath}; the Docker contract tests must run here, not skip."
          )
        }
        assumeTrue(false, "no reachable Docker daemon; skipping contract test")
      }
      ensureBusybox()
      try {
        runBlocking { withTimeout(TEST_TIMEOUT_MS) { block(connector) } }
      } finally {
        runBlocking { reap(connector.containers) }
      }
    }
  }

  private fun labels() = mapOf(ownerLabel to runToken)

  private suspend fun reap(containers: ContainerApi) {
    for (summary in containers.list(labels = labels())) {
      runCatching { containers.remove(summary.id, force = true) }
    }
  }

  private fun List<LogFrame>.textOf(stream: LogStream): String =
      filter { it.stream == stream }
          .joinToString(separator = "") { it.bytes.toString(Charsets.UTF_8) }

  @Test
  fun `stdout and stderr are routed to their own streams`() = withConnector { connector ->
    val created =
        connector.containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", "echo to-out; echo to-err 1>&2"),
            labels = labels(),
            autoRemove = false,
        )
    connector.containers.start(created.id)
    connector.containers.wait(created.id)

    val frames = connector.logs.logs(created.id, follow = false).toList()
    val out = frames.textOf(LogStream.STDOUT)
    val err = frames.textOf(LogStream.STDERR)
    assertTrue("to-out" in out, "stdout frame should carry the stdout line, got: $out")
    assertTrue("to-err" in err, "stderr frame should carry the stderr line, got: $err")
    assertTrue("to-err" !in out, "the stderr line must not appear on stdout")
  }

  @Test
  fun `follow mode completes when the container exits`() = withConnector { connector ->
    val created =
        connector.containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", "echo hello-follow"),
            labels = labels(),
            autoRemove = false,
        )
    connector.containers.start(created.id)
    // toList() only returns if the flow completes — i.e. the daemon closed the followed stream.
    val frames = connector.logs.logs(created.id, follow = true).toList()
    assertTrue(
        frames.textOf(LogStream.STDOUT).contains("hello-follow"),
        "followed logs should include the output and then complete",
    )
  }

  @Test
  fun `per-stream ordering holds under heavy interleaved output`() = withConnector { connector ->
    val n = 400
    val created =
        connector.containers.create(
            image = BUSYBOX,
            cmd =
                listOf(
                    "sh",
                    "-c",
                    "i=0; while [ \$i -lt $n ]; do echo o-\$i; echo e-\$i 1>&2; i=\$((i+1)); done",
                ),
            labels = labels(),
            autoRemove = false,
        )
    connector.containers.start(created.id)
    val frames = connector.logs.logs(created.id, follow = true).toList()

    val outSeq = sequenceNumbers(frames.textOf(LogStream.STDOUT), "o-")
    val errSeq = sequenceNumbers(frames.textOf(LogStream.STDERR), "e-")
    // Completeness + ordering: each stream must be exactly 0,1,…,n-1 in arrival order.
    assertEquals((0 until n).toList(), outSeq, "stdout lines must arrive complete and in order")
    assertEquals((0 until n).toList(), errSeq, "stderr lines must arrive complete and in order")
  }

  @Test
  fun `TTY containers are decoded as a single raw stdout stream`() = withConnector { connector ->
    val created =
        connector.containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", "echo tty-hello"),
            labels = labels(),
            autoRemove = false,
            tty = true,
        )
    connector.containers.start(created.id)
    connector.containers.wait(created.id)

    val frames = connector.logs.logs(created.id, follow = false).toList()
    assertTrue(
        frames.all { it.stream == LogStream.STDOUT },
        "TTY output is all one raw stdout stream",
    )
    assertTrue("tty-hello" in frames.textOf(LogStream.STDOUT))
  }

  @Test
  fun `cancelling a follow leaks no threads and leaves the connection usable`() =
      withConnector { connector ->
        val created =
            connector.containers.create(
                image = BUSYBOX,
                cmd = listOf("sh", "-c", "while true; do echo tick; sleep 0.05; done"),
                labels = labels(),
                autoRemove = false,
            )
        connector.containers.start(created.id)

        suspend fun openAndCancelFollow() {
          kotlinx.coroutines.coroutineScope {
            val job = launch {
              connector.logs.logs(created.id, follow = true).collect {
                // Cancel this collection as soon as we've proven bytes flow.
                throw kotlinx.coroutines.CancellationException("got a frame; stop following")
              }
            }
            job.join()
          }
        }

        // Warm up first: event-loop and coroutine thread pools grow lazily to a steady size on the
        // first few connections. A *leak* is unbounded growth beyond that, which the measured phase
        // below detects — warmup separates "pool reached its size" from "each cycle leaks a
        // thread".
        repeat(WARMUP_CANCELS) { openAndCancelFollow() }
        System.gc()
        Thread.sleep(SETTLE_MS)
        val threadsBefore = Thread.activeCount()

        repeat(REPEAT_CANCELS) { openAndCancelFollow() }
        // A wedged/leaked connection would make this ping hang (caught by the suite timeout) or
        // fail.
        connector.ping()
        System.gc()
        Thread.sleep(SETTLE_MS)
        val threadsAfter = Thread.activeCount()
        println(
            "[docker-connector] follow open/cancel threads: before=$threadsBefore " +
                "after=$threadsAfter over $REPEAT_CANCELS post-warmup cycles (steady == no leak)"
        )
        assertTrue(
            threadsAfter <= threadsBefore + THREAD_SLACK,
            "thread count grew from $threadsBefore to $threadsAfter across $REPEAT_CANCELS " +
                "post-warmup open/cancel cycles — a follow is leaking threads",
        )

        connector.containers.stop(created.id, timeout = kotlin.time.Duration.ZERO)
      }

  private fun sequenceNumbers(text: String, prefix: String): List<Int> =
      text
          .split("\n")
          .filter { it.startsWith(prefix) }
          .map { it.removePrefix(prefix).trim().toInt() }

  private fun ensureBusybox() {
    runCatching {
          ProcessBuilder("docker", "pull", BUSYBOX)
              .redirectErrorStream(true)
              .start()
              .also { it.inputStream.readBytes() }
              .waitFor()
        }
        .getOrNull()
  }

  private companion object {
    const val BUSYBOX = "busybox:latest"
    const val TEST_TIMEOUT_MS = 120_000L
    // Netty starts its event-loop threads lazily and assigns them round-robin, so the worker pool
    // keeps growing (up to 2×cores) for the first many connections. Warm up well past that ceiling
    // so the measured window observes the steady state, where a real leak would still show.
    const val WARMUP_CANCELS = 80
    const val REPEAT_CANCELS = 40
    const val SETTLE_MS = 500L
    const val THREAD_SLACK = 4
  }
}
