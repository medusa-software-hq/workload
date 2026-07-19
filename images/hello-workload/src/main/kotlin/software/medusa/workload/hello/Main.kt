package software.medusa.workload.hello

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.StorageOptions
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * The image the manual end-to-end test runs. It is a **deliberately ordinary** GCS client: it reads
 * one object with `google-cloud-storage` and prints a fingerprint of it. There is **no auth code
 * and no token handling here** — the library resolves credentials via Application Default
 * Credentials, which inside a `workload run` means the metadata server the CLI runs (M4 "Beacon"),
 * and refreshes them on its own. That's the whole point of the test: a normal Google-library app,
 * oblivious to the broker and to refresh, "just works" — exactly as it would on a real GCE VM.
 *
 * Config (all from the profile's env, since `workload run` never overrides the image's command):
 * - `HELLO_PROBE_GCS` `gs://bucket/object` to read (the real-resource proof). Required to read.
 * - `HELLO_FINGERPRINT_CHARS` sha256 prefix length (default 12).
 * - `HELLO_EXIT_CODE` exit with this instead of 0 — makes exit-code passthrough testable by hand.
 */
fun main() {
  banner()
  println("arch:  ${System.getProperty("os.arch")}")

  val readOk = reportGcsRead()
  reportEnvironment()

  // A failed read is a failed test: surface it as a non-zero exit unless the profile forces one.
  val forced = System.getenv("HELLO_EXIT_CODE")?.toIntOrNull()
  val exit = forced ?: if (readOk) 0 else 1
  println()
  println("exiting with $exit")
  exitProcess(exit)
}

private fun banner() {
  val line = "=".repeat(46)
  println(line)
  println(" hello-workload (Kotlin + google-cloud-storage)")
  println(line)
}

/**
 * Reads the object named by `HELLO_PROBE_GCS` with an ordinary [StorageOptions] client and prints a
 * fingerprint of its bytes. Returns true on success. All the auth — detecting the metadata server,
 * fetching and refreshing the token — happens inside the library, invisibly to this code.
 */
private fun reportGcsRead(): Boolean {
  println()
  val uri = System.getenv("HELLO_PROBE_GCS")
  if (uri.isNullOrBlank()) {
    println("gcs: HELLO_PROBE_GCS not set — nothing to read (set it to gs://bucket/object).")
    return true
  }

  val blob = parseGcsUri(uri)
  if (blob == null) {
    println("gcs: HELLO_PROBE_GCS is not a gs://bucket/object URI: $uri")
    return false
  }

  return try {
    val storage = StorageOptions.getDefaultInstance().service
    val bytes = storage.readAllBytes(blob)
    println("gcs: READ OK — $uri (${bytes.size} bytes)")
    println("  content fingerprint: ${fingerprint(bytes)}")
    println(
        "  -> a live GCS object was read via ADC. No token in this process; the metadata server " +
            "served and refreshed it for us — the workload never knew.",
    )
    true
  } catch (e: Exception) {
    // Any failure (metadata unreachable, missing IAM, no such object) is a red test — print the
    // library's own message and fail.
    println("gcs: READ FAILED — ${e.message}")
    println("  (${e.javaClass.simpleName}) — the brokered credential couldn't read $uri.")
    false
  }
}

/**
 * A compact view of the environment the workload received, by fingerprint (never value). Its job is
 * to show the two Beacon-era facts: the access token is **not** here (only the `GCE_METADATA_*`
 * pointers are), and any worker-resolved secret **is**.
 */
private fun reportEnvironment() {
  println()
  val hasToken = !System.getenv("GOOGLE_OAUTH_ACCESS_TOKEN").isNullOrEmpty()
  println("env: GOOGLE_OAUTH_ACCESS_TOKEN present? $hasToken   (Beacon expects: false)")
  System.getenv("GCE_METADATA_HOST")?.let { println("env: GCE_METADATA_HOST = $it") }

  val interesting =
      System.getenv().filterKeys { it !in ignoredVars && !it.startsWith("GCE_METADATA_") }
  if (interesting.isNotEmpty()) {
    println("env (values are never printed — compare the fingerprint):")
    for ((name, value) in interesting.toSortedMap()) {
      println("  %-28s %6d  %s".format(name, value.length, fingerprint(value.toByteArray())))
    }
  }
}

// Noise from the OS/shell and the JRE base image — not profile-supplied, so not worth
// fingerprinting.
private val ignoredVars =
    setOf(
        "PATH",
        "HOME",
        "HOSTNAME",
        "PWD",
        "SHLVL",
        "TERM",
        "LANG",
        "LANGUAGE",
        "LC_ALL",
        "JAVA_HOME",
        "JAVA_VERSION",
    )

private fun parseGcsUri(uri: String): BlobId? {
  if (!uri.startsWith("gs://")) return null
  val rest = uri.removePrefix("gs://")
  val bucket = rest.substringBefore('/', "")
  val obj = rest.substringAfter('/', "")
  if (bucket.isEmpty() || obj.isEmpty()) return null
  return BlobId.of(bucket, obj)
}

private fun fingerprint(bytes: ByteArray): String {
  val chars = System.getenv("HELLO_FINGERPRINT_CHARS")?.toIntOrNull() ?: 12
  val hex =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  return hex.take(chars)
}
