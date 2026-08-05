package software.medusa.workload.systemtest

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The output (and exit code, if it finished) captured from a CLI invocation. */
internal data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
  val combined: String
    get() = stdout + stderr
}

/**
 * Finds the repo root by walking up from the current working directory looking for the top-level
 * `settings.gradle.kts` — robust to Gradle's `Test` task working directory (the `system-test`
 * module dir) without hardcoding a `../` depth.
 */
internal fun repoRoot(): Path {
  var dir = Path.of("").toAbsolutePath()
  while (true) {
    if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
    dir = dir.parent ?: error("Could not find the repo root (no settings.gradle.kts in any parent)")
  }
}

/**
 * The real, shipped CLI binary this suite drives — the checked-in wrapper script at
 * `cli/bin/workload-cli`, which execs `cli/build/install/cli/bin/cli` (the Gradle `application`
 * plugin's `installDist` output; see `cli/Taskfile.yml`'s `install` task). Not built here: a system
 * test that silently rebuilt the CLI would test whatever the working tree happens to contain at
 * assertion time, not the artifact CI actually validated — the caller (Taskfile/CI job) builds it
 * first with `task cli:install` / `./gradlew :cli:installDist`.
 */
internal fun cliBinaryPath(): Path {
  val path = repoRoot().resolve("cli/bin/workload-cli")
  check(Files.isExecutable(path)) {
    "The CLI binary isn't built at $path. Run '../gradlew :cli:installDist' " +
        "(or 'task cli:install') before running the system-test suite."
  }
  return path
}

/**
 * A running or finished CLI subprocess. Output is pumped continuously into thread-safe buffers from
 * the moment the process starts, so a caller can inspect what a *still-running* process has printed
 * so far (e.g. polling for a "Metadata: http://..." readiness line, or counting refresh log lines)
 * without racing a `waitFor`.
 */
internal class CliProcess
private constructor(private val process: Process, val args: List<String>) {
  private val stdoutLines = CopyOnWriteArrayList<String>()
  private val stderrLines = CopyOnWriteArrayList<String>()

  init {
    pump(process.inputStream, stdoutLines)
    pump(process.errorStream, stderrLines)
  }

  private fun pump(stream: java.io.InputStream, into: CopyOnWriteArrayList<String>) {
    val thread = Thread {
      BufferedReader(InputStreamReader(stream)).use { reader ->
        reader.lineSequence().forEach { into.add(it) }
      }
    }
    thread.isDaemon = true
    thread.start()
  }

  val isAlive: Boolean
    get() = process.isAlive

  val pid: Long
    get() = process.pid()

  /** What the process has written so far, joined with newlines — safe to call while still alive. */
  fun stderrSoFar(): String = stderrLines.joinToString("\n")

  fun stdoutSoFar(): String = stdoutLines.joinToString("\n")

  /** Blocks until [line] appears in stderr (readiness lines are all stderr — see `main.kt`). */
  fun awaitStderrLine(contains: String, timeoutSeconds: Long = 30): Boolean {
    val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
    while (System.nanoTime() < deadline) {
      if (stderrLines.any { contains in it }) return true
      if (!isAlive) return stderrLines.any { contains in it }
      Thread.sleep(200)
    }
    return stderrLines.any { contains in it }
  }

  /** How many stderr lines matched [contains] so far — for refresh-count style assertions. */
  fun stderrLineCount(contains: String): Int = stderrLines.count { contains in it }

  /** Waits up to [timeoutSeconds] for the process to exit, then snapshots its captured output. */
  fun waitFor(timeoutSeconds: Long = 60): CliResult {
    process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    val exit = if (process.isAlive) -1 else process.exitValue()
    return CliResult(exit, stdoutSoFar(), stderrSoFar())
  }

  /**
   * SIGTERM, then SIGKILL after [graceSeconds] if it's still alive — for a still-running exec/run.
   */
  fun stop(graceSeconds: Long = 10) {
    if (!process.isAlive) return
    process.destroy()
    if (!process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      process.waitFor(graceSeconds, TimeUnit.SECONDS)
    }
  }

  companion object {
    /**
     * Starts the CLI in the background; the caller drives its lifecycle via the returned handle.
     */
    fun start(args: List<String>, env: Map<String, String>, stdin: String? = null): CliProcess {
      val builder =
          ProcessBuilder(listOf(cliBinaryPath().toString()) + args)
              .redirectInput(
                  if (stdin != null) ProcessBuilder.Redirect.PIPE
                  else ProcessBuilder.Redirect.INHERIT
              )
      builder.environment().putAll(env)
      val process = builder.start()
      if (stdin != null) {
        process.outputStream.use { it.write(stdin.toByteArray()) }
      }
      return CliProcess(process, args)
    }

    /** Runs the CLI to completion (or [timeoutSeconds]) and returns its captured result. */
    fun run(
        args: List<String>,
        env: Map<String, String>,
        stdin: String? = null,
        timeoutSeconds: Long = 60,
    ): CliResult = start(args, env, stdin).waitFor(timeoutSeconds)
  }
}
