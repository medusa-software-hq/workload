package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class AdminFormatTest {
  @Test
  fun `formatTimestamp trims iso to minute precision in UTC`() {
    assertEquals("2026-07-17 14:08 UTC", formatTimestamp("2026-07-17T14:08:44.512979Z"))
  }

  @Test
  fun `formatTimestamp handles blanks and passes junk through`() {
    assertEquals("—", formatTimestamp(""))
    assertEquals("not-a-date", formatTimestamp("not-a-date"))
  }

  @Test
  fun `shortEnum strips the STATUS prefix and kebab-cases`() {
    assertEquals("active", shortEnum("WORKER_STATUS_ACTIVE"))
    assertEquals("binding-missing", shortEnum("VERIFICATION_STATUS_BINDING_MISSING"))
    assertEquals("unresolvable", shortEnum("IMAGE_STATUS_UNRESOLVABLE"))
    assertEquals("—", shortEnum(""))
  }

  @Test
  fun `spec keeps only the mutable revision fields`() {
    val revision =
        AdminProfileRevision(
            profileId = "p",
            revision = 3,
            targetServiceAccount = "sa@x.iam.gserviceaccount.com",
            createdBy = "admin@medusa.software",
            note = "hello",
            verificationStatus = "VERIFICATION_STATUS_VERIFIED",
            envVars = mapOf("MODE" to "batch"),
            secretEnvVars = mapOf("DEMO" to "projects/p/secrets/s/versions/latest"),
            dockerImage = "repo/img:tag",
            dockerImageDigest = "sha256:abc",
        )
    val spec = specFromRevision(revision)
    assertEquals("sa@x.iam.gserviceaccount.com", spec.targetServiceAccount)
    assertEquals("hello", spec.note)
    assertEquals("repo/img:tag", spec.dockerImage)
    assertEquals(mapOf("MODE" to "batch"), spec.envVars)
    assertEquals(mapOf("DEMO" to "projects/p/secrets/s/versions/latest"), spec.secretEnvVars)

    // Server-assigned fields must not appear in the round-trippable spec.
    val encoded = specJson.encodeToString(spec)
    for (leaked in listOf("revision", "createdBy", "verificationStatus", "dockerImageDigest")) {
      assertTrue(leaked !in encoded, "spec should not carry $leaked")
    }
  }

  @Test
  fun `worker sections group by state, with activity from live runs`() {
    val table =
        formatWorkerSections(
            listOf(
                AdminWorker(
                    workerId = "w-1",
                    name = "jakub-mac",
                    status = "WORKER_STATUS_ACTIVE",
                    grantedProfileIds = listOf("hand-test-1", "test-2"),
                    createdAt = "2026-07-17T14:08:44.5Z",
                ),
                AdminWorker(
                    workerId = "w-2",
                    status = "WORKER_STATUS_PENDING",
                    sourceIp = "203.0.113.7",
                    createdAt = "2026-07-18T00:00:00Z",
                ),
            ),
            liveRuns =
                listOf(AdminRun(workerId = "w-1", state = "RUN_STATE_RUNNING", profileId = "p-1")),
            includeRevoked = false,
        )
    assertTrue(table.contains("Pending approval:"))
    assertTrue(table.contains("Workers:"))
    // Active worker: activity is the fresh-running count, grants joined, created formatted.
    assertTrue(table.contains("jakub-mac"))
    assertTrue(table.contains("● 1 running"))
    assertTrue(table.contains("hand-test-1,test-2"))
    assertTrue(table.contains("2026-07-17 14:08 UTC"))
    // Pending worker shows its source IP.
    assertTrue(table.contains("203.0.113.7"))
  }

  @Test
  fun `revoked workers are hidden by default with a footnote, shown with the flag`() {
    val workers =
        listOf(
            AdminWorker(workerId = "w-1", name = "active-one", status = "WORKER_STATUS_ACTIVE"),
            AdminWorker(
                workerId = "w-2",
                name = "gone",
                status = "WORKER_STATUS_REVOKED",
                revokedAt = "2026-07-19T09:12:00Z",
                createdAt = "2026-07-10T00:00:00Z",
            ),
        )
    val hidden = formatWorkerSections(workers, emptyList(), includeRevoked = false)
    assertTrue(!hidden.contains("Revoked workers:"))
    assertTrue(hidden.contains("--include-revoked"))

    val shown = formatWorkerSections(workers, emptyList(), includeRevoked = true)
    assertTrue(shown.contains("Revoked workers:"))
    assertTrue(shown.contains("gone") && shown.contains("2026-07-19 09:12 UTC"))
  }

  @Test
  fun `profile table has an ACTIVE RUNS column fed by live runs`() {
    val table =
        formatProfileTable(
            listOf(
                AdminProfile("flow-worker", "Flow", 4, archived = false, createdAt = "2026-07-20")
            ),
            listOf(
                AdminRun(
                    workerName = "tux",
                    state = "RUN_STATE_RUNNING",
                    profileId = "flow-worker",
                ),
                AdminRun(
                    workerName = "mac",
                    state = "RUN_STATE_RUNNING",
                    profileId = "flow-worker",
                ),
                // A lost run must NOT count toward "running".
                AdminRun(workerName = "old", state = "RUN_STATE_LOST", profileId = "flow-worker"),
            ),
        )
    assertTrue(table.lines()[0].contains("ACTIVE RUNS"))
    assertTrue(table.contains("● 2 (tux, mac)"))
  }

  @Test
  fun `run table shows profile@rev, state, and exit, with dashes for lost and no-exit`() {
    val table =
        formatRunTable(
            listOf(
                AdminRun(
                    runId = "r-1",
                    workerName = "tux",
                    profileId = "flow-worker",
                    revision = 4,
                    kind = "RUN_KIND_RUN",
                    state = "RUN_STATE_RUNNING",
                    startedAt = "2026-07-22T10:00:00Z",
                ),
                AdminRun(
                    runId = "r-2",
                    workerName = "mac",
                    profileId = "hand-test-1",
                    revision = 5,
                    kind = "RUN_KIND_EXEC",
                    state = "RUN_STATE_SUCCEEDED",
                    exitCode = 0,
                    hasExitCode = true,
                    startedAt = "2026-07-22T09:00:00Z",
                    endedAt = "2026-07-22T09:04:01Z",
                ),
                AdminRun(
                    runId = "r-3",
                    workerName = "mac",
                    profileId = "hand-test-1",
                    revision = 5,
                    kind = "RUN_KIND_RUN",
                    state = "RUN_STATE_LOST",
                    startedAt = "2026-07-19T00:00:00Z",
                ),
            )
        )
    assertTrue(table.lines()[0].startsWith("RUN ID"))
    assertTrue(
        table.contains("flow-worker@4") && table.contains("run") && table.contains("running")
    )
    // succeeded exec run shows its exit code and a computed duration.
    assertTrue(
        table.contains("hand-test-1@5") && table.contains("succeeded") && table.contains("4m01s")
    )
    // lost run: no duration, no exit.
    val lostLine = table.lines().single { it.startsWith("r-3") }
    assertTrue(lostLine.contains("lost") && lostLine.trimEnd().endsWith("—"))
  }

  @Test
  fun `runsJson round-trips a run for scripts like roll-worker`() {
    val run =
        AdminRun(
            runId = "r-1",
            workerId = "w-1",
            workerName = "tux",
            profileId = "flow-worker",
            revision = 4,
            kind = "RUN_KIND_RUN",
            state = "RUN_STATE_RUNNING",
            startedAt = "2026-07-22T10:00:00Z",
        )
    val encoded = runsJson.encodeToString(listOf(run))
    assertEquals(listOf(run), runsJson.decodeFromString<List<AdminRun>>(encoded))
    assertTrue(encoded.contains("\"state\":\"RUN_STATE_RUNNING\""))
  }

  @Test
  fun `revokeWarning fires only when the worker has running runs`() {
    assertEquals(null, revokeWarning("w-1", 0))
    val warning = revokeWarning("w-1", 2)
    assertTrue(warning!!.contains("w-1") && warning.contains("2 live run"))
    assertTrue(warning.contains("lost"))
  }

  @Test
  fun `formatRelative and formatRunDuration are human-friendly`() {
    val now = java.time.Instant.parse("2026-07-22T12:00:00Z")
    assertEquals("never", formatRelative("", now))
    assertEquals("30s ago", formatRelative("2026-07-22T11:59:30Z", now))
    assertEquals("5m ago", formatRelative("2026-07-22T11:55:00Z", now))
    assertEquals("2h ago", formatRelative("2026-07-22T10:00:00Z", now))
    assertEquals("3d ago", formatRelative("2026-07-19T12:00:00Z", now))

    assertEquals("45s", formatRunDuration("2026-07-22T11:59:15Z", "", now))
    assertEquals("4m01s", formatRunDuration("2026-07-22T09:00:00Z", "2026-07-22T09:04:01Z", now))
    assertEquals("3h12m", formatRunDuration("2026-07-22T08:48:00Z", "", now))
  }

  @Test
  fun `revision detail shows image, pin, and sorted env`() {
    val detail =
        formatRevisionDetail(
            AdminProfileRevision(
                profileId = "hand-test-1",
                revision = 3,
                targetServiceAccount = "it-handtest@x.iam.gserviceaccount.com",
                verificationStatus = "VERIFICATION_STATUS_VERIFIED",
                dockerImage = "repo/hello:latest",
                dockerImageDigest = "sha256:deadbeef",
                imageStatus = "IMAGE_STATUS_RESOLVED",
                envVars = mapOf("MODE" to "batch"),
            ),
            isLatest = true,
            total = 3,
        )
    assertTrue("3 of 3 (latest)" in detail)
    assertTrue("sha256:deadbeef" in detail)
    assertTrue("resolved" in detail)
    assertTrue("MODE=batch" in detail)
  }
}
