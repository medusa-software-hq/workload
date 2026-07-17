package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
  fun `worker table renders status, grants, and formatted dates`() {
    val table =
        formatWorkerTable(
            listOf(
                AdminWorker(
                    workerId = "w-1",
                    name = "jakub-mac",
                    status = "WORKER_STATUS_ACTIVE",
                    grantedProfileIds = listOf("hand-test-1", "test-2"),
                    createdAt = "2026-07-17T14:08:44.5Z",
                ),
                AdminWorker(workerId = "w-2", status = "WORKER_STATUS_PENDING"),
            )
        )
    val lines = table.lines()
    assertTrue(lines[0].startsWith("WORKER ID"))
    assertTrue(lines[1].contains("jakub-mac") && lines[1].contains("active"))
    assertTrue(lines[1].contains("hand-test-1,test-2"))
    assertTrue(lines[1].contains("2026-07-17 14:08 UTC"))
    // Missing name / grants render as em dashes.
    assertTrue(lines[2].contains("pending") && lines[2].contains("—"))
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
