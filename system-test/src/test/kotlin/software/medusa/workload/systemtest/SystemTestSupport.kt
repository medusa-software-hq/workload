package software.medusa.workload.systemtest

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import software.medusa.workload.v1.WorkerStatus

/** Marks generated fixture names/notes as this harness's own, and nothing else's. */
internal const val artifactPrefix = "systest-"

/**
 * How old a leftover fixture has to be before [sweepStaleArtifacts] reclaims it. Generous, so a
 * slow-but-still-running test run never has its own fixtures swept out from under it (test runs
 * that go this long are already a problem worth seeing fail some other way).
 */
private val staleAfter: Duration = Duration.ofHours(2)

/**
 * A short, lowercase, dash-safe id unique to one test run, so parallel runs (and a crashed prior
 * run's leftovers) never collide — see `ProfileId.profileIdPattern`.
 */
internal fun newRunToken(): String = artifactPrefix + UUID.randomUUID().toString().take(10)

/**
 * Best-effort cleanup of anything this harness left behind — a crashed prior run, a killed CI job.
 * Run at the start *and* end of every test class (per the issue's ask): start-of-run sweeping means
 * a flaky prior failure can't wedge every subsequent run; end-of-run sweeping keeps steady-state
 * fleet listings clean between runs. Scoped strictly to the [artifactPrefix] namespace — never
 * touches anything a human created.
 */
internal suspend fun sweepStaleArtifacts(admin: AdminClient, now: Instant = Instant.now()) {
  val cutoff = now.minus(staleAfter)
  fun isStale(createdAt: String): Boolean =
      runCatching { Instant.parse(createdAt) }.getOrNull()?.isBefore(cutoff) ?: true

  admin
      .listWorkers()
      .filter { it.name.startsWith(artifactPrefix) && isStale(it.createdAt) }
      .forEach { worker ->
        runCatching {
          when (worker.status) {
            WorkerStatus.WORKER_STATUS_ACTIVE -> admin.revokeWorker(worker.workerId)
            WorkerStatus.WORKER_STATUS_PENDING -> admin.rejectWorker(worker.workerId)
            else -> Unit // already rejected/revoked — nothing to sweep
          }
        }
      }

  admin
      .listProfiles()
      .filter { it.profileId.startsWith(artifactPrefix) && !it.archived && isStale(it.createdAt) }
      .forEach { profile -> runCatching { admin.archiveProfile(profile.profileId) } }

  admin
      .listEnrollmentTokens()
      .filter { it.note.startsWith(artifactPrefix) && isStale(it.createdAt) }
      .forEach { token -> runCatching { admin.revokeEnrollmentToken(token.enrollmentTokenId) } }
}

internal data class RawResponse(val status: Int, val body: String)

/**
 * Posts directly to the worker plane (bypassing the CLI) — for asserting the raw, bare-404 shape
 * the unprobeable worker plane returns, which the CLI deliberately smooths into a friendlier
 * message before a human ever sees it (see `RegisterCommand.registerErrorMessage`).
 */
internal fun postWorkerPlane(
    baseUrl: String,
    path: String,
    bearer: String?,
    body: String,
): RawResponse {
  val builder =
      HttpRequest.newBuilder(URI.create("$baseUrl$path"))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .header("Content-Type", "application/json")
  bearer?.let { builder.header("Authorization", "Bearer $it") }
  val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  return RawResponse(response.statusCode(), response.body())
}

private val httpClient: HttpClient = HttpClient.newHttpClient()

/**
 * Shared per-test-class scaffolding: resolves the [Target] once, sweeps stale fixtures before and
 * after, and tracks/tears down everything a test creates (workers, profiles, enrollment tokens,
 * live CLI subprocesses) regardless of whether the test passed. `PER_CLASS` lifecycle so the
 * before/after-all hooks can be plain instance methods and share `target`/bookkeeping with the
 * `@Test` methods without static state.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal abstract class SystemTestBase {
  protected val runToken: String = newRunToken()
  protected lateinit var target: Target

  private val liveProcesses = mutableListOf<CliProcess>()
  private val ownedWorkerIds = mutableListOf<String>()
  private val ownedProfileIds = mutableListOf<String>()
  private val ownedEnrollmentTokenIds = mutableListOf<String>()

  @BeforeAll
  fun setUpTarget() {
    target = Target.current()
    runBlocking { sweepStaleArtifacts(target.admin) }
  }

  @AfterAll
  fun tearDownFixtures() {
    liveProcesses.forEach { runCatching { it.stop() } }
    runBlocking {
      ownedWorkerIds.forEach { runCatching { target.admin.revokeWorker(it) } }
      ownedProfileIds.forEach { runCatching { target.admin.archiveProfile(it) } }
      ownedEnrollmentTokenIds.forEach { runCatching { target.admin.revokeEnrollmentToken(it) } }
      sweepStaleArtifacts(target.admin)
    }
  }

  private val idCounter = java.util.concurrent.atomic.AtomicInteger()

  /**
   * A fresh, dash-safe id under this run's namespace — unique per *call*, not just per class, so
   * sibling @Test methods (same [runToken], `PER_CLASS` lifecycle) never collide on a shared
   * default like `id("profile")`.
   */
  protected fun id(kind: String) = "$runToken-$kind-${idCounter.incrementAndGet()}"

  /**
   * Registers a worker id for teardown when it was created some way other than [registerWorker]
   * (e.g. a raw HTTP redemption in a negative-path test).
   */
  protected fun trackWorker(workerId: String) {
    ownedWorkerIds += workerId
  }

  protected fun freshConfigDir(): Path = Files.createTempDirectory("$runToken-config-")

  /** Mints an enrollment token and remembers it for teardown. */
  protected fun issueEnrollmentToken(note: String, requireApproval: Boolean = false): String =
      runBlocking {
        val (token, meta) =
            target.admin.createEnrollmentToken("$runToken-$note", requireApproval = requireApproval)
        ownedEnrollmentTokenIds += meta.enrollmentTokenId
        token
      }

  /**
   * A pure exec/env profile — no container image, so it's exercisable hermetically (see the
   * `Target.Local`/[ContainerFixture] doc: a hermetic stack has no real registry to pull from).
   */
  protected fun createExecProfile(
      profileId: String = id("profile"),
      envVars: Map<String, String> = emptyMap(),
  ): String = runBlocking {
    target.admin.createExecProfile(
        profileId,
        targetServiceAccount = "$profileId@systest.iam.gserviceaccount.com",
        envVars = envVars,
    )
    ownedProfileIds += profileId
    profileId
  }

  protected fun grant(workerId: String, profileId: String) = runBlocking {
    target.admin.grantProfile(workerId, profileId)
  }

  /**
   * Registers a worker via the real CLI (`workload worker register`) and waits for it to settle
   * (active, or — for a require-approval token — the poll timeout, which the caller then approves
   * past out-of-band). Returns the worker's admin-visible id once it exists.
   */
  protected fun registerWorker(
      configDir: Path,
      enrollmentToken: String,
      name: String = id("worker"),
      timeoutSeconds: Long = 30,
  ): CliResult {
    val result =
        cli(
            "worker",
            "register",
            "--name",
            name,
            "--enrollment-token",
            enrollmentToken,
            configDir = configDir,
            timeoutSeconds = timeoutSeconds,
        )
    runBlocking { target.admin.findWorkerByName(name)?.let { ownedWorkerIds += it.workerId } }
    return result
  }

  protected fun cli(
      vararg args: String,
      configDir: Path,
      extraEnv: Map<String, String> = emptyMap(),
      timeoutSeconds: Long = 60,
  ): CliResult =
      CliProcess.run(
          args.toList(),
          env = target.cliEnv + target.configEnv(configDir) + extraEnv,
          timeoutSeconds = timeoutSeconds,
      )

  /** Starts a long-running CLI invocation (`exec`/`run`) the test drives interactively. */
  protected fun cliStart(
      vararg args: String,
      configDir: Path,
      extraEnv: Map<String, String> = emptyMap(),
  ): CliProcess {
    val process =
        CliProcess.start(
            args.toList(),
            env = target.cliEnv + target.configEnv(configDir) + extraEnv,
        )
    liveProcesses += process
    return process
  }

  /** Polls [check] until it returns non-null or [timeoutSeconds] elapses. */
  protected fun <T> awaitCondition(
      timeoutSeconds: Long,
      intervalSeconds: Long = 3,
      check: suspend () -> T?,
  ): T = runBlocking {
    withTimeout(timeoutSeconds.seconds) {
      var result: T? = null
      while (result == null) {
        result = check()
        if (result == null) delay(intervalSeconds.seconds)
      }
      result
    }
  }
}
