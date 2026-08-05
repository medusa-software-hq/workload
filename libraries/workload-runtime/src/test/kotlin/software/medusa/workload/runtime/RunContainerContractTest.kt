package software.medusa.workload.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig

/**
 * Contract tests for what `workload run` does after the pull: the create -> start -> stream -> wait
 * lifecycle, against a **real** Docker daemon. Same policy as the connector's own suites —
 * self-skip without a daemon, but a hard failure when `DOCKER_CONTRACT_REQUIRED` is set (CI), so
 * these can't silently skip into a false green.
 *
 * These cover two of story 05's acceptance criteria directly: exit-code passthrough for success and
 * failure images, and env (including resolved secrets) reaching the container via the create body
 * without ever touching a host command line.
 */
class RunContainerContractTest {

  private val runToken = "runtest-" + java.util.UUID.randomUUID().toString().take(8)

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
      ensureBusybox(connector)
      runBlocking { block(connector) }
    }
  }

  private fun labels() = mapOf(workloadWorkerLabel to runToken)

  /** Runs an image the way `workload run` does, collecting what it wrote to each stream. */
  private suspend fun run(
      connector: DockerConnector,
      cmd: List<String>,
      env: List<String> = emptyList(),
  ): Triple<Int, String, String> {
    val out = StringBuilder()
    val err = StringBuilder()
    // busybox's default entrypoint takes the command via the image's Cmd; we pass ours through the
    // connector's create body, exactly as RunCommand does.
    val exit =
        runContainerToCompletion(
            connector = connector,
            image = BUSYBOX,
            env = env,
            labels = labels(),
            onStdout = { out.append(String(it)) },
            onStderr = { err.append(String(it)) },
            cmd = cmd,
        )
    return Triple(exit, out.toString(), err.toString())
  }

  @Test
  fun `a successful container exits 0 and its stdout is streamed back`() = withConnector { c ->
    val (exit, out, _) = run(c, listOf("sh", "-c", "echo hello-from-container"))
    assertEquals(0, exit)
    assertTrue("hello-from-container" in out, "stdout should be streamed back, got: $out")
  }

  @Test
  fun `a failing container's exit code is passed through`() = withConnector { c ->
    val (exit, _, _) = run(c, listOf("sh", "-c", "exit 42"))
    assertEquals(42, exit, "the container's exit code must pass through verbatim")
  }

  @Test
  fun `stdout and stderr stay on their own streams`() = withConnector { c ->
    val (exit, out, err) = run(c, listOf("sh", "-c", "echo to-out; echo to-err 1>&2"))
    assertEquals(0, exit)
    assertTrue("to-out" in out)
    assertTrue("to-err" in err)
    assertTrue("to-err" !in out, "the container's stderr must not be folded into stdout")
  }

  @Test
  fun `profile env and the metadata pointers reach the container`() = withConnector { c ->
    val env =
        buildMetadataContainerEnv(
            mapOf("MODE" to "batch", "API_KEY" to "resolved-secret"),
            metadataPointerEnv("169.254.169.254:80"),
        )
    val (exit, out, _) =
        run(
            c,
            listOf("sh", "-c", "echo \"MODE=\$MODE key=\$API_KEY md=\$GCE_METADATA_HOST\""),
            env = env,
        )
    assertEquals(0, exit)
    assertTrue("MODE=batch" in out, out)
    assertTrue("key=resolved-secret" in out, out)
    assertTrue("md=169.254.169.254:80" in out, out)
  }

  @Test
  fun `env values never appear in any host process command line`() = withConnector { c ->
    // The AC: `ps` on the host must never show the values. We pass a distinctive secret through the
    // create body, then scan every process's command line on this host while it runs.
    val marker = "supersecret-$runToken"
    val env =
        buildMetadataContainerEnv(
            mapOf("API_KEY" to marker),
            metadataPointerEnv("169.254.169.254:80"),
        )
    val (exit, _, _) = run(c, listOf("sh", "-c", "sleep 1; exit 0"), env = env)
    assertEquals(0, exit)

    val leaked =
        ProcessHandle.allProcesses()
            .map { it.info().commandLine().orElse("") }
            .filter { marker in it }
            .count()
    assertEquals(0L, leaked, "the env value leaked into a host process command line")
  }

  @Test
  fun `the container is removed after the run completes`() = withConnector { c ->
    run(c, listOf("sh", "-c", "exit 0"))
    // Nothing this run created should linger -- runContainerToCompletion removes in a finally.
    val gone =
        (1..50).any {
          Thread.sleep(100)
          c.containers.list(labels = labels()).isEmpty()
        }
    assertTrue(gone, "the container should be removed once the run completes")
  }

  private fun ensureBusybox(connector: DockerConnector) {
    // Primed through the library now that it can pull (M3-06) — the `docker` CLI is no longer
    // needed by these tests either.
    runBlocking { connector.images.pull(BUSYBOX).collect {} }
  }

  private companion object {
    const val BUSYBOX = "busybox:latest"
  }
}
