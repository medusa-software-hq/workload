package software.medusa.workload.systemtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.workload.v1.RunState

/**
 * Mid-run revocation (issue AC 3): revoke a worker while its container/exec runs, and assert that
 * the credential's next refresh fails and the run is observed as `LOST`.
 *
 * The child here loops hitting the metadata emulator's token endpoint — exactly what a real,
 * long-lived workload does to refresh its credential (see `MetadataEmulator`) — printing
 * `REFRESH_OK`/`REFRESH_FAIL` per attempt, so a post-revocation failure is directly observable in
 * the CLI's own captured output rather than inferred.
 */
internal class MidRunRevocationSystemTest : SystemTestBase() {

  @Test
  fun `revoking a worker mid-run fails its next refresh and the run reads LOST`() {
    val profileId = createExecProfile()
    val token = issueEnrollmentToken("mid-run-revoke")
    val configDir = freshConfigDir()
    val workerName = id("worker-revoked")

    val registered = registerWorker(configDir, token, name = workerName)
    assertEquals(0, registered.exitCode, "register failed: ${registered.combined}")
    val worker = checkNotNull(runBlocking { target.admin.findWorkerByName(workerName) })
    grant(worker.workerId, profileId)

    val refreshLoop =
        """
        i=0
        while [ ${'$'}i -lt 40 ]; do
          if curl -sf -H "Metadata-Flavor: Google" "http://${'$'}GCE_METADATA_HOST/computeMetadata/v1/instance/service-accounts/default/token" >/dev/null; then
            echo REFRESH_OK
          else
            echo REFRESH_FAIL
          fi
          sleep 5
          i=${'$'}((i+1))
        done
        """
            .trimIndent()
    val exec =
        cliStart(
            "worker",
            "exec",
            "-p",
            profileId,
            "--",
            "sh",
            "-c",
            refreshLoop,
            configDir = configDir,
        )

    // The run must actually be live (and have refreshed OK at least once) before revoking, or a
    // later LOST/REFRESH_FAIL observation wouldn't prove anything.
    awaitCondition(timeoutSeconds = 30) {
      target.admin.listRuns(workerId = worker.workerId, liveOnly = true).firstOrNull {
        it.state == RunState.RUN_STATE_RUNNING
      }
    }
    assertTrue(
        exec.awaitStderrLine("Metadata:", timeoutSeconds = 10),
        "exec never started the metadata emulator",
    )
    awaitCondition(timeoutSeconds = 20) { if ("REFRESH_OK" in exec.stdoutSoFar()) true else null }

    runBlocking { target.admin.revokeWorker(worker.workerId) }

    // lostAfter (RunPresence.kt) is 3x the 30s heartbeat interval = 90s of no heartbeat before a
    // still-RUNNING run reads as LOST; poll comfortably past that.
    val lost =
        awaitCondition(timeoutSeconds = 150, intervalSeconds = 5) {
          target.admin.listRuns(workerId = worker.workerId).firstOrNull {
            it.state == RunState.RUN_STATE_LOST
          }
        }
    assertEquals(RunState.RUN_STATE_LOST, lost.state)

    val failedAfterRevoke =
        awaitCondition(timeoutSeconds = 30) {
          if ("REFRESH_FAIL" in exec.stdoutSoFar()) true else null
        }
    assertTrue(
        failedAfterRevoke,
        "expected a refresh to fail after revocation: ${exec.stdoutSoFar()}",
    )

    exec.stop()
  }
}
