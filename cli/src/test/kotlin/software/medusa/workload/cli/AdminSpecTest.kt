package software.medusa.workload.cli

import com.github.ajalt.clikt.core.PrintMessage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminSpecTest {
  @Test
  fun `reads a spec from stdin`() {
    val spec =
        readSpec("-") {
          """{"targetServiceAccount":"sa@x","dockerImage":"r/i:t","envVars":{"K":"V"}}"""
        }
    assertEquals("sa@x", spec.targetServiceAccount)
    assertEquals("r/i:t", spec.dockerImage)
    assertEquals(mapOf("K" to "V"), spec.envVars)
  }

  @Test
  fun `reads a spec from a file`() {
    val file = Files.createTempFile("spec", ".json")
    Files.writeString(file, """{"targetServiceAccount":"sa@y"}""")
    try {
      assertEquals(
          "sa@y",
          readSpec(file.toString()) { error("stdin not used") }.targetServiceAccount,
      )
    } finally {
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun `show --json output round-trips back into readSpec`() {
    val original =
        ProfileRevisionSpec(
            targetServiceAccount = "sa@x",
            note = "a note",
            dockerImage = "repo/img:tag",
            envVars = mapOf("MODE" to "batch"),
            secretEnvVars = mapOf("DEMO" to "projects/p/secrets/s/versions/latest"),
        )
    val emitted = specJson.encodeToString(original)
    assertEquals(original, readSpec("-") { emitted })
  }

  @Test
  fun `unknown keys are tolerated`() {
    assertEquals(
        "sa",
        readSpec("-") { """{"targetServiceAccount":"sa","revision":9}""" }.targetServiceAccount,
    )
  }

  @Test
  fun `missing target service account is rejected`() {
    assertFailsWith<PrintMessage> { readSpec("-") { """{"note":"hi"}""" } }
  }

  @Test
  fun `invalid json is rejected`() {
    assertFailsWith<PrintMessage> { readSpec("-") { "not json" } }
  }

  @Test
  fun `unreadable file is rejected`() {
    assertFailsWith<PrintMessage> { readSpec("/no/such/spec/file.json") { error("unused") } }
  }
}
