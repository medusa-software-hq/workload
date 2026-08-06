package software.medusa.workload.systemtest

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

/**
 * A job that outlives the brokered token's lifetime refreshes at least once (issue AC 4). The
 * worker-side refresh counter and its structured log line already exist (`RefreshingTokenCache`,
 * `event=beacon.token.refresh` — see `cli/src/main/resources/logback.xml`); this asserts against
 * that log line directly in the CLI subprocess's own captured stderr, rather than re-deriving
 * refresh evidence some other way.
 *
 * Locally, [LocalStack] mints tokens with an [localTokenLifetimeSeconds]-second lifetime (real
 * default: 900s / 15 minutes — see `WorkerTokenBrokerService`) precisely so this leg is a matter of
 * seconds in PR CI. Against staging the real ~15-minute lifetime is what's actually exercised, so
 * this leg genuinely runs that long there — that's the point of the nightly schedule rather than PR
 * CI: a real long-lived job outliving a real token lifetime, not a simulation of one.
 */
internal class LongRunTokenRefreshSystemTest : SystemTestBase() {

  @Test
  @Timeout(20, unit = TimeUnit.MINUTES)
  fun `a job outliving the token lifetime refreshes at least once`() {
    val profileId = createExecProfile()
    val token = issueEnrollmentToken("long-run-refresh")
    val configDir = freshConfigDir()
    val workerName = id("worker-longrun")

    val registered = registerWorker(configDir, token, name = workerName)
    assertEquals(0, registered.exitCode, "register failed: ${registered.combined}")
    val worker = checkNotNull(runBlocking { target.admin.findWorkerByName(workerName) })
    grant(worker.workerId, profileId)

    // Staging's real 900s lifetime needs the job to run past ~15 minutes; local's shortened
    // lifetime (localTokenLifetimeSeconds = 8s) needs only tens of seconds. The point either way:
    // probe well past the TTL, on an interval short enough to observe more than one refresh.
    //
    // Locally we run a generous window (80s ≈ 10 token lifetimes) probing every 2s rather than a
    // tight 36s/3s: at 12×3s the margin over the "1 initial claim + >=1 renewal" bar was thin
    // enough that CI scheduling jitter occasionally logged <2 refreshes, flaking the gate. A wide
    // window with frequent probes makes >=2 refreshes reliable without weakening the assertion.
    val (probeIntervalSeconds, iterations) = if (target.label == "staging") 60 to 17 else 2 to 40

    val probeLoop =
        """
        i=0
        while [ ${'$'}i -lt $iterations ]; do
          curl -sf -H "Metadata-Flavor: Google" "http://${'$'}GCE_METADATA_HOST/computeMetadata/v1/instance/service-accounts/default/token" >/dev/null
          sleep $probeIntervalSeconds
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
            probeLoop,
            configDir = configDir,
        )

    val timeoutSeconds = (probeIntervalSeconds * iterations + 60).toLong()
    val result = exec.waitFor(timeoutSeconds)
    assertEquals(0, result.exitCode, "the probe loop should exit cleanly: ${result.combined}")

    val refreshes = exec.stderrLineCount("event=beacon.token.refresh")
    assertTrue(
        refreshes >= 2,
        "expected at least 2 refreshes (1 initial claim + >=1 renewal) logged via " +
            "'event=beacon.token.refresh', saw $refreshes. Full stderr:\n${result.stderr}",
    )
  }
}
