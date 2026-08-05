package software.medusa.workload.systemtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The negative legs from the issue's coverage list: a burnt enrollment token, and claiming a
 * profile the worker isn't granted (or that's been archived).
 */
internal class NegativePathsSystemTest : SystemTestBase() {

  @Test
  fun `a reused enrollment token is a bare 404`() {
    val token = issueEnrollmentToken("burnt-token")
    val first =
        postWorkerPlane(
            target.workerApiBaseUrl,
            "/worker/v2/registrations",
            bearer = token,
            body = """{"name":"${id("first-redeem")}"}""",
        )
    assertEquals(200, first.status, "the token's first redemption should succeed: ${first.body}")
    runBlocking {
      target.admin.findWorkerByName(id("first-redeem"))?.let { trackWorker(it.workerId) }
    }

    // Same token again: already burnt. The worker plane is unprobeable by design (see
    // `RegistrationServiceV2`/`v2EnrollmentTokenDrop`) — every rejection reads as the identical
    // bare 404, so a scanner (or an attacker replaying a captured token) learns nothing.
    val second =
        postWorkerPlane(
            target.workerApiBaseUrl,
            "/worker/v2/registrations",
            bearer = token,
            body = """{"name":"${id("second-redeem")}"}""",
        )
    assertEquals(404, second.status, "a burnt token should be refused")
    assertTrue(
        second.body.isEmpty(),
        "the worker plane's 404 must carry no body, got: '${second.body}'",
    )
  }

  @Test
  fun `claiming an ungranted profile is refused`() {
    val profileId = createExecProfile()
    val token = issueEnrollmentToken("ungranted")
    val configDir = freshConfigDir()
    val workerName = id("worker-ungranted")

    val registered = registerWorker(configDir, token, name = workerName)
    assertEquals(0, registered.exitCode, "register failed: ${registered.combined}")
    // Deliberately no grant() call — the whole point of this leg.

    val result = cli("worker", "token", "-p", profileId, configDir = configDir)
    assertEquals(1, result.exitCode, "claiming an ungranted profile should fail")
    assertTrue(
        "don't have access" in result.stderr,
        "expected the not_granted message, got: ${result.stderr}",
    )
  }

  @Test
  fun `claiming an archived profile is refused`() {
    val profileId = createExecProfile()
    val token = issueEnrollmentToken("archived")
    val configDir = freshConfigDir()
    val workerName = id("worker-archived")

    val registered = registerWorker(configDir, token, name = workerName)
    assertEquals(0, registered.exitCode, "register failed: ${registered.combined}")
    val worker = checkNotNull(runBlocking { target.admin.findWorkerByName(workerName) })
    grant(worker.workerId, profileId)
    runBlocking { target.admin.archiveProfile(profileId) }

    val result = cli("worker", "token", "-p", profileId, configDir = configDir)
    assertEquals(1, result.exitCode, "claiming an archived profile should fail")
    assertTrue(
        "archived" in result.stderr,
        "expected the profile_archived message, got: ${result.stderr}",
    )
  }
}
