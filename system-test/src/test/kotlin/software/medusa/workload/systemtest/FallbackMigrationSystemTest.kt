package software.medusa.workload.systemtest

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The migrate-on-arrival demo (workload#122 parts 1-2, see `FallbackPlacementReconciler`): a
 * fallback-tagged profile running on the shared fallback node migrates off automatically the
 * instant a dedicated node appears for it — driven entirely through the real CLI binary and
 * asserted through the admin gRPC client, exactly like [HappyPathSystemTest].
 *
 * The two workers this test registers stand in for the real deployment shape this proves out: a
 * cloud VM permanently flagged [software.medusa.workload.v1.Worker.getFallbackNode] (see
 * `infra/gcp-fallback-node.tf`) and an ordinary worker (a developer's laptop) that comes online
 * later and gets a dedicated grant. The reconciler can't tell the difference between this harness's
 * two CLI-registered workers and those two real machines — placement is driven purely by
 * `fallback_node`/`fallback_eligible` flags and grant/assignment records, never by where a worker
 * physically runs — so this is a faithful end-to-end exercise of the same migration the real fleet
 * relies on.
 */
internal class FallbackMigrationSystemTest : SystemTestBase() {

  @Test
  fun `profile on the fallback node migrates off the instant a dedicated node appears`() {
    val profileId = createExecProfile()
    markFallbackEligible(profileId)

    // Stand in for the real cloud fallback node: an active worker flagged fallback_node = true.
    val fallbackToken = issueEnrollmentToken("fallback-node")
    val fallbackConfigDir = freshConfigDir()
    val fallbackNodeName = id("cloud-fallback-node")
    val fallbackRegistered =
        registerWorker(fallbackConfigDir, fallbackToken, name = fallbackNodeName)
    assertTrue(
        fallbackRegistered.exitCode == 0,
        "fallback node register failed: ${fallbackRegistered.combined}",
    )
    val fallbackWorker =
        checkNotNull(runBlocking { target.admin.findWorkerByName(fallbackNodeName) }) {
          "fallback node '$fallbackNodeName' not visible via admin ListWorkers after registering"
        }
    markFallbackNode(fallbackWorker.workerId)

    // No grant was ever issued by hand — the reconciler auto-places the fallback-eligible profile
    // onto the (only) fallback node the moment both flags are set.
    val placedOnFallback =
        awaitCondition(timeoutSeconds = 30) {
          target.admin.findWorkerByName(fallbackNodeName)?.takeIf {
            profileId in it.grantedProfileIdsList
          }
        }
    assertTrue(
        profileId in placedOnFallback.grantedProfileIdsList,
        "profile did not auto-place onto the fallback node",
    )

    // A laptop VM comes online and an admin gives it a dedicated grant — a dedicated node
    // appearing for this profile.
    val laptopToken = issueEnrollmentToken("laptop-node")
    val laptopConfigDir = freshConfigDir()
    val laptopName = id("laptop")
    val laptopRegistered = registerWorker(laptopConfigDir, laptopToken, name = laptopName)
    assertTrue(
        laptopRegistered.exitCode == 0,
        "laptop register failed: ${laptopRegistered.combined}",
    )
    val laptopWorker =
        checkNotNull(runBlocking { target.admin.findWorkerByName(laptopName) }) {
          "laptop node '$laptopName' not visible via admin ListWorkers after registering"
        }
    grant(laptopWorker.workerId, profileId)

    // The fallback grant is revoked automatically, with no separate migrate/cleanup step.
    val migratedOff =
        awaitCondition(timeoutSeconds = 30) {
          target.admin.findWorkerByName(fallbackNodeName)?.takeIf {
            profileId !in it.grantedProfileIdsList
          }
        }
    assertTrue(
        profileId !in migratedOff.grantedProfileIdsList,
        "profile did not migrate off the fallback node once the laptop node was granted it",
    )

    val laptopAfterGrant = checkNotNull(runBlocking { target.admin.findWorkerByName(laptopName) })
    assertTrue(
        profileId in laptopAfterGrant.grantedProfileIdsList,
        "profile not granted on the dedicated (laptop) node",
    )
  }
}
