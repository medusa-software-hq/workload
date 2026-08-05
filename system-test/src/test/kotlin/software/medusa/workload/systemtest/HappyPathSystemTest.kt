package software.medusa.workload.systemtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.workload.v1.RunState

/**
 * The core happy path (issue AC 1): enroll a worker, claim a profile, run a workload, assert
 * success — driven entirely through the real CLI binary, asserted through the admin gRPC client.
 *
 * "Run a workload" means `workload worker run` (a real container) only when the target has a real,
 * pullable image (see [ContainerFixture] — staging only, via Terraform-provisioned fixtures); a
 * hermetic stack has no Google registry to pull from by design (`requireValidImageRef` — see
 * `FleetServiceImpl` — refuses anything else server-side, not a harness shortcut), so locally this
 * exercises `workload worker exec` instead. Both go through the identical claim ->
 * metadata-emulator -> run-report lifecycle; only the payload (a container vs. a host command)
 * differs.
 */
internal class HappyPathSystemTest : SystemTestBase() {

  @Test
  fun `register, approve, grant, exec, succeeds`() {
    val profileId = createExecProfile(envVars = mapOf("GREETING" to "hello-from-systest"))
    val token = issueEnrollmentToken("happy-path", requireApproval = false)
    val configDir = freshConfigDir()
    val workerName = id("worker")

    val registered = registerWorker(configDir, token, name = workerName)
    assertEquals(0, registered.exitCode, "register failed: ${registered.combined}")
    assertTrue(
        "Active." in registered.stdout,
        "expected auto-approve to settle active: ${registered.stdout}",
    )

    val worker = runBlocking { target.admin.findWorkerByName(workerName) }
    checkNotNull(worker) {
      "worker '$workerName' not visible via admin ListWorkers after registering"
    }
    grant(worker.workerId, profileId)

    val result =
        cli(
            "worker",
            "exec",
            "-p",
            profileId,
            "--",
            "sh",
            "-c",
            "echo \"greeting=\$GREETING\" && exit 0",
            configDir = configDir,
        )
    assertEquals(0, result.exitCode, "exec failed: ${result.combined}")
    assertTrue(
        "greeting=hello-from-systest" in result.stdout,
        "profile env var missing: ${result.stdout}",
    )

    val run =
        awaitCondition(timeoutSeconds = 30) {
          target.admin.listRuns(workerId = worker.workerId, profileId = profileId).firstOrNull()
        }
    assertEquals(RunState.RUN_STATE_SUCCEEDED, run.state, "run did not end SUCCEEDED: $run")
    assertEquals(0, run.exitCode)
  }

  @Test
  fun `run a real container image`() {
    val fixture = target.containerFixture
    if (fixture == null) {
      println("SKIPPED: no ContainerFixture for target '${target.label}' — see README.md")
      return
    }

    val profileId = id("image-profile")
    runBlocking {
      target.admin.createImageProfile(profileId, fixture.targetServiceAccount, fixture.imageRef)
    }
    val token = issueEnrollmentToken("happy-path-run", requireApproval = false)
    val configDir = freshConfigDir()
    val workerName = id("worker-run")

    val registered = registerWorker(configDir, token, name = workerName)
    assertEquals(0, registered.exitCode, "register failed: ${registered.combined}")

    val worker = checkNotNull(runBlocking { target.admin.findWorkerByName(workerName) })
    grant(worker.workerId, profileId)

    val result = cli("worker", "run", "-p", profileId, configDir = configDir, timeoutSeconds = 300)
    assertEquals(0, result.exitCode, "run failed: ${result.combined}")

    val run =
        awaitCondition(timeoutSeconds = 30) {
          target.admin.listRuns(workerId = worker.workerId, profileId = profileId).firstOrNull()
        }
    assertEquals(RunState.RUN_STATE_SUCCEEDED, run.state, "run did not end SUCCEEDED: $run")
  }
}
