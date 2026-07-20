package software.medusa.workload.hello

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.StorageOptions
import java.security.MessageDigest
import java.util.Base64
import kotlin.system.exitProcess

/**
 * The image the manual end-to-end test runs. It is a **deliberately ordinary** GCS client: it reads
 * one object with `google-cloud-storage` and prints a fingerprint of it. There is **no auth code
 * and no token handling here** — the library resolves credentials via Application Default
 * Credentials, which inside a `workload run` means the metadata server the CLI runs (M4 "Beacon"),
 * and refreshes them on its own. That's the whole point of the test: a normal Google-library app,
 * oblivious to the broker and to refresh, "just works" — exactly as it would on a real GCE VM.
 *
 * It also mints an **ID token** the same way a hosted Flow worker does — `IdTokenCredentials` over
 * ADC — so one run proves both metadata paths a workload uses: the access-token path (GCS) and the
 * `identity` path (audience-bound OIDC token).
 *
 * **It behaves like a worker: it does not exit on its own.** A worker's exit has no settled meaning
 * in this system yet, and a workload that exits in milliseconds isn't representative (it also hid a
 * `workload run` bug that only bit containers living past ~15s). So after the one-time proofs this
 * stays up and **heartbeats**, re-proving the credential keeps working (refresh included) until a
 * human or `workload run`'s teardown stops it.
 *
 * Config (all from the profile's env, since `workload run` never overrides the image's command):
 * - `HELLO_PROBE_GCS` `gs://bucket/object` to read (the real-resource proof). Required to read.
 * - `HELLO_IDTOKEN_AUDIENCE` audience for the ID-token proof (default a placeholder URL).
 * - `HELLO_HEARTBEAT_SECONDS` seconds between heartbeats (default 30).
 * - `HELLO_MAX_SECONDS` stop (exit 0/1) after this many seconds — for bounded/automated runs. Unset
 *   ⇒ run until stopped, like a real worker.
 * - `HELLO_FINGERPRINT_CHARS` sha256 prefix length (default 12).
 * - `HELLO_EXIT_CODE` exit immediately after the one-time proofs with this code — keeps exit-code
 *   passthrough testable by hand (bypasses worker mode).
 */
fun main() {
  banner()
  println("arch:  ${System.getProperty("os.arch")}")

  val startupOk = reportGcsRead() && reportIdToken()
  reportEnvironment()

  // HELLO_EXIT_CODE forces an immediate exit (exit-code passthrough test) — bypasses worker mode.
  System.getenv("HELLO_EXIT_CODE")?.toIntOrNull()?.let {
    println()
    println("exiting with $it (HELLO_EXIT_CODE)")
    exitProcess(it)
  }

  runWorkerLoop(startupOk)
}

/**
 * Stays up like a worker, heartbeating every `HELLO_HEARTBEAT_SECONDS`: each beat re-reads the GCS
 * object and re-mints the ID token, so the log shows the credential is still valid as the run
 * outlives token lifetimes (the emulator refreshes underneath). Runs until stopped, unless
 * `HELLO_MAX_SECONDS` bounds it — then it exits 0 if startup + the last beat passed, else 1.
 */
private fun runWorkerLoop(startupOk: Boolean): Nothing {
  val heartbeatSeconds =
      (System.getenv("HELLO_HEARTBEAT_SECONDS")?.toLongOrNull() ?: 30L).coerceAtLeast(1)
  val maxSeconds = System.getenv("HELLO_MAX_SECONDS")?.toLongOrNull()
  println()
  println(
      "worker: up (startup ${if (startupOk) "OK" else "FAILED"}). Heartbeating every " +
          "${heartbeatSeconds}s; " +
          (maxSeconds?.let { "stopping after ${it}s." }
              ?: "running until stopped — a worker doesn't exit on its own."),
  )
  Runtime.getRuntime().addShutdownHook(Thread { println("worker: shutting down.") })

  val startMillis = System.currentTimeMillis()
  var beat = 0
  var lastOk = startupOk
  while (true) {
    Thread.sleep(heartbeatSeconds * 1000L)
    beat++
    val elapsed = (System.currentTimeMillis() - startMillis) / 1000
    val gcsOk = probeGcs()
    val idToken = probeIdToken()
    lastOk = gcsOk && idToken != null
    val idStatus = idToken?.let { "OK ${fingerprint(it.toByteArray())}" } ?: "FAIL"
    println(
        "heartbeat #$beat  t=${elapsed}s  gcs=${if (gcsOk) "OK" else "FAIL"}  idtoken=$idStatus"
    )
    if (maxSeconds != null && elapsed >= maxSeconds) {
      println("worker: reached HELLO_MAX_SECONDS ($maxSeconds) — exiting.")
      exitProcess(if (startupOk && lastOk) 0 else 1)
    }
  }
}

/** Quiet GCS re-read for a heartbeat: true if the object read (or nothing to read). */
private fun probeGcs(): Boolean {
  val uri = System.getenv("HELLO_PROBE_GCS")
  if (uri.isNullOrBlank()) return true
  val blob = parseGcsUri(uri) ?: return false
  return runCatching { StorageOptions.getDefaultInstance().service.readAllBytes(blob) }.isSuccess
}

/**
 * Quiet ID-token re-mint for a heartbeat: the JWT if it minted with the right audience, else null.
 */
private fun probeIdToken(): String? {
  val audience = System.getenv("HELLO_IDTOKEN_AUDIENCE")?.ifBlank { null } ?: defaultAudience
  val jwt = runCatching { mintIdToken(audience) }.getOrNull() ?: return null
  return jwt.takeIf { jwtClaim(it, "aud") == audience }
}

private const val defaultAudience = "https://hello-workload.example.test"

private fun banner() {
  val line = "=".repeat(46)
  println(line)
  println(" hello-workload (Kotlin + google-auth: GCS + ID token)")
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
 * Mints an audience-bound OIDC **ID token** via `IdTokenCredentials` over ADC — byte-for-byte the
 * path a hosted Flow worker uses (`GoogleCredentials.getApplicationDefault() as IdTokenProvider`,
 * with `INCLUDE_EMAIL` + `FORMAT_FULL` so the SA email survives the compute-engine metadata path).
 * Prints the token's claims (never the token) — proving the `identity` endpoint works end to end,
 * with real google-auth, in a container. Returns true on success.
 */
private fun reportIdToken(): Boolean {
  println()
  val audience = System.getenv("HELLO_IDTOKEN_AUDIENCE")?.ifBlank { null } ?: defaultAudience
  return try {
    val jwt = mintIdToken(audience)

    println("idtoken: MINTED via IdTokenCredentials — the same path a hosted Flow worker uses.")
    println("  audience: ${jwtClaim(jwt, "aud") ?: "(missing!)"}")
    println(
        "  email:    ${jwtClaim(jwt, "email") ?: "(none — INCLUDE_EMAIL/FORMAT_FULL didn't add it)"}"
    )
    println("  issuer:   ${jwtClaim(jwt, "iss") ?: "(unknown)"}")
    println("  token fingerprint: ${fingerprint(jwt.toByteArray())}")

    if (jwtClaim(jwt, "aud") != audience) {
      println("  MISMATCH — aud is not the audience we requested ($audience).")
      return false
    }
    true
  } catch (e: Exception) {
    println("idtoken: MINT FAILED — ${e.message}")
    println(
        "  (${e.javaClass.simpleName}) — the metadata identity endpoint or the ADC ID-token path " +
            "failed. This is the path a Flow worker's credential minting takes.",
    )
    false
  }
}

/**
 * Mints an audience-bound ID token over ADC — the shared core of [reportIdToken] and
 * [probeIdToken]. `getApplicationDefault() as IdTokenProvider` + `INCLUDE_EMAIL`/`FORMAT_FULL` is
 * exactly a Flow worker's path. Throws if ADC isn't an [IdTokenProvider] or the mint fails.
 */
private fun mintIdToken(audience: String): String {
  val provider =
      GoogleCredentials.getApplicationDefault() as? IdTokenProvider
          ?: error("ADC did not resolve to an IdTokenProvider")
  val credentials =
      IdTokenCredentials.newBuilder()
          .setIdTokenProvider(provider)
          .setTargetAudience(audience)
          .setOptions(
              listOf(IdTokenProvider.Option.INCLUDE_EMAIL, IdTokenProvider.Option.FORMAT_FULL),
          )
          .build()
  credentials.refresh()
  return credentials.idToken.tokenValue
}

/** A single top-level string claim from a JWT's payload, or null. Never logs the token itself. */
private fun jwtClaim(jwt: String, name: String): String? {
  val parts = jwt.split(".")
  if (parts.size < 2) return null
  val payload =
      runCatching { String(Base64.getUrlDecoder().decode(parts[1])) }.getOrNull() ?: return null
  return Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(payload)?.groupValues?.get(1)
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
