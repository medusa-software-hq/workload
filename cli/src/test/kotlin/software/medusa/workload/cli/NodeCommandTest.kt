package software.medusa.workload.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.parse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeCommandTest {

  private val tempDir = Files.createTempDirectory("ms-workload-node-test")

  @AfterTest
  fun cleanup() {
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }

  // ---------------------------------------------------------------------
  // renderNodeCloudInit
  // ---------------------------------------------------------------------

  @Test
  fun `renderNodeCloudInit substitutes exactly the three placeholders`() {
    val template = "env=\${workload_environment} name=\${node_name} v=\${cli_version}"

    val rendered =
        renderNodeCloudInit(
            template,
            NodeTemplateVars(workloadEnvironment = "staging", nodeName = "n1", cliVersion = "v9"),
        )

    assertEquals("env=staging name=n1 v=v9", rendered)
  }

  @Test
  fun `renderNodeCloudInit leaves unrelated dollar-brace text untouched`() {
    // A shell default-value expansion the node's own boot scripts might contain — must survive
    // rendering unchanged, the way node.yaml.tmpl's embedded scripts rely on bare $VAR to do.
    val template = "\${workload_environment} but not \${SOME_OTHER_VAR:-default}"

    val rendered =
        renderNodeCloudInit(
            template,
            NodeTemplateVars(workloadEnvironment = "prod", nodeName = "", cliVersion = ""),
        )

    assertEquals("prod but not \${SOME_OTHER_VAR:-default}", rendered)
  }

  @Test
  fun `the bundled template renders with no placeholders left over`() {
    val rendered =
        renderNodeCloudInit(
            loadNodeCloudInitTemplate(),
            NodeTemplateVars(
                workloadEnvironment = "staging",
                nodeName = "test-node-1",
                cliVersion = "v1.2.3",
            ),
        )

    assertFalse(rendered.contains("\${workload_environment}"))
    assertFalse(rendered.contains("\${node_name}"))
    assertFalse(rendered.contains("\${cli_version}"))
    assertTrue(rendered.contains("WORKLOAD_ENVIRONMENT=staging"))
    assertTrue(rendered.contains("WORKLOAD_NODE_NAME=test-node-1"))
    assertTrue(rendered.contains("WORKLOAD_CLI_VERSION=v1.2.3"))
    // The identity-volume mechanism: present regardless of how this render is used.
    assertTrue(rendered.contains("WLIDENTITY"))
    assertTrue(rendered.contains("workload-node-identity-sync"))
  }

  // ---------------------------------------------------------------------
  // writeNodeArtifacts
  // ---------------------------------------------------------------------

  private val noIso = IsoBuilder { _, _, _ -> null }

  @Test
  fun `writeNodeArtifacts with bootMedia writes user-data, meta-data and the identity token`() {
    val outDir = tempDir.resolve("node-1")

    val artifacts =
        writeNodeArtifacts(
            outDir = outDir,
            nodeName = "node-1",
            workloadEnvironment = "prod",
            cliVersion = "latest",
            enrollmentToken = "wle_abc123",
            bootMedia = true,
            isoBuilder = noIso,
        )

    assertEquals(outDir.resolve("user-data"), artifacts.userData)
    assertEquals(outDir.resolve("meta-data"), artifacts.metaData)
    assertNull(artifacts.bootIso)
    assertNull(artifacts.identityIso)

    assertTrue(Files.readString(artifacts.userData!!).contains("WORKLOAD_NODE_NAME=node-1"))
    assertEquals(
        "instance-id: node-1\nlocal-hostname: node-1\n",
        Files.readString(artifacts.metaData!!),
    )
    assertEquals("wle_abc123", Files.readString(artifacts.enrollmentTokenFile))
  }

  @Test
  fun `writeNodeArtifacts with identity-only skips boot media`() {
    val outDir = tempDir.resolve("node-2")

    val artifacts =
        writeNodeArtifacts(
            outDir = outDir,
            nodeName = "node-2",
            workloadEnvironment = "prod",
            cliVersion = "latest",
            enrollmentToken = "wle_rotate",
            bootMedia = false,
            isoBuilder = noIso,
        )

    assertNull(artifacts.userData)
    assertNull(artifacts.metaData)
    assertFalse(Files.exists(outDir.resolve("user-data")))
    assertEquals("wle_rotate", Files.readString(artifacts.enrollmentTokenFile))
  }

  @Test
  fun `writeNodeArtifacts sets 0600 on the enrollment token and 0700 on its directory`() {
    val outDir = tempDir.resolve("node-3")

    val artifacts =
        writeNodeArtifacts(
            outDir = outDir,
            nodeName = "node-3",
            workloadEnvironment = "prod",
            cliVersion = "latest",
            enrollmentToken = "wle_secret",
            bootMedia = false,
            isoBuilder = noIso,
        )

    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(artifacts.enrollmentTokenFile),
    )
    assertEquals(
        PosixFilePermissions.fromString("rwx------"),
        Files.getPosixFilePermissions(artifacts.enrollmentTokenFile.parent),
    )
  }

  @Test
  fun `writeNodeArtifacts uses whatever the IsoBuilder returns`() {
    val outDir = tempDir.resolve("node-4")
    val fakeIso = tempDir.resolve("fake.iso")
    val builder = IsoBuilder { output, _, _ ->
      if (output.fileName.toString() == "identity.iso") fakeIso else null
    }

    val artifacts =
        writeNodeArtifacts(
            outDir = outDir,
            nodeName = "node-4",
            workloadEnvironment = "prod",
            cliVersion = "latest",
            enrollmentToken = "wle_iso",
            bootMedia = true,
            isoBuilder = builder,
        )

    assertEquals(fakeIso, artifacts.identityIso)
    assertNull(artifacts.bootIso)
  }

  // ---------------------------------------------------------------------
  // defaultNodeOutDir
  // ---------------------------------------------------------------------

  @Test
  fun `defaultNodeOutDir is scoped by node name`() {
    assertEquals(Path.of("node-my-node"), defaultNodeOutDir("my-node"))
  }

  // ---------------------------------------------------------------------
  // driver validation
  // ---------------------------------------------------------------------

  @Test
  fun `node create rejects an unsupported driver before minting anything`() {
    assertFailsWith<BadParameterValue> { NodeCreateCommand().parse(arrayOf("--driver", "gce")) }
  }

  @Test
  fun `node start rejects an unsupported driver`() {
    assertFailsWith<BadParameterValue> {
      NodeStartCommand().parse(arrayOf("--name", "n1", "--driver", "gce"))
    }
  }

  @Test
  fun `node start requires a name`() {
    assertFailsWith<Exception> { NodeStartCommand().parse(arrayOf("--driver", "utm")) }
  }
}
