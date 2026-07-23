package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

// ---------------------------------------------------------------------------
// Shared plumbing
// ---------------------------------------------------------------------------

/** The default human token source: the cached browser sign-in for [env], refreshed silently. */
internal fun humanTokenProvider(env: Environment): () -> String {
  val session = AdminSession(dir = env.configDir, refresher = defaultRefresher(env))
  return { session.currentIdToken() }
}

/**
 * Resolves the ID-token source for an admin call given the chosen [method]. [AdminAuthMethod.GSI]
 * uses the cached human browser sign-in; [AdminAuthMethod.SA] uses ambient service credentials
 * (ADC/WIF) — a clean error when none can mint an ID token. There is deliberately no auto-detection
 * (see [AdminAuthMethod]). The two provider factories are injectable so the selection is
 * unit-testable without real credentials.
 */
internal fun adminTokenProvider(
    env: Environment,
    method: AdminAuthMethod,
    service: (String) -> (() -> String)? = { serviceIdTokenProvider(it) },
    human: (Environment) -> (() -> String) = ::humanTokenProvider,
): () -> String =
    when (method) {
      AdminAuthMethod.GSI -> human(env)
      AdminAuthMethod.SA ->
          service(env.apiBaseUrl)
              ?: throw PrintMessage(
                  "Service-account auth (sa) found no ambient credentials able to mint an ID " +
                      "token. On a dev box run 'gcloud auth application-default login' with a " +
                      "service account (or impersonation); in CI, authenticate via Workload " +
                      "Identity Federation. To use your own Google account, use gsi (the default).",
                  statusCode = 1,
                  printError = true,
              )
    }

/** The `[verbose]`-mode one-liner describing how this admin call will authenticate. */
internal fun verboseAuthLine(env: Environment, method: AdminAuthMethod): String =
    when (method) {
      AdminAuthMethod.GSI -> {
        val who =
            loadAdminCredentials(env.configDir)?.email?.let { "as $it" }
                ?: "(no cached session — run 'workload admin login')"
        "auth: gsi — Google Sign-In $who; token audience ${env.oauthClientId}"
      }
      AdminAuthMethod.SA ->
          "auth: sa — ambient service credentials (ADC/WIF); token audience ${env.apiBaseUrl}"
    }

/**
 * Shared base for the admin **API** action commands (everything except login/logout, which are
 * inherently human / purely local). Injects the [Environment], resolves the auth method (`--auth`
 * override, else `$WORKLOAD_AUTH_METHOD`, else GSI), and hands back an authenticated client via
 * [client].
 */
abstract class AdminActionCommand(name: String) : CliktCommand(name) {
  protected val env by requireEnvironment()
  private val cli by requireObject<CliContext>()
  private val authOverride by
      option(
              "--auth",
              help =
                  "Force the auth method for this call: gsi (Google Sign-In, the default) or sa " +
                      "(service account via ambient ADC/WIF). Overrides \$WORKLOAD_AUTH_METHOD.",
          )
          .choice("gsi" to AdminAuthMethod.GSI, "sa" to AdminAuthMethod.SA)

  private fun authMethod(): AdminAuthMethod =
      authOverride ?: cli.authMethodDefault ?: AdminAuthMethod.GSI

  protected fun client(): AdminApiClient {
    val method = authMethod()
    if (cli.verbose) echo(verboseAuthLine(env, method), err = true)
    return AdminApiClient(env.apiBaseUrl, idTokenProvider = adminTokenProvider(env, method))
  }
}

/** Turns the two expected admin failures into clean, actionable CLI errors. */
private inline fun <T> runAdmin(block: () -> T): T =
    try {
      block()
    } catch (e: AdminNotLoggedInException) {
      throw PrintMessage(e.message ?: "Not signed in.", statusCode = 1, printError = true)
    } catch (e: AdminApiException) {
      throw PrintMessage(e.message ?: "Admin API error.", statusCode = 1, printError = true)
    }

/** Reads a [ProfileRevisionSpec] from a file path, or stdin when [source] is `-`. */
internal fun readSpec(source: String, stdin: () -> String): ProfileRevisionSpec {
  val text =
      if (source == "-") {
        stdin()
      } else {
        runCatching { File(source).readText() }
            .getOrElse {
              throw PrintMessage(
                  "Can't read $source: ${it.message}",
                  statusCode = 1,
                  printError = true,
              )
            }
      }
  val spec =
      try {
        specDecodeJson.decodeFromString<ProfileRevisionSpec>(text)
      } catch (e: Exception) {
        throw PrintMessage("Invalid spec JSON: ${e.message}", statusCode = 1, printError = true)
      }
  if (spec.targetServiceAccount.isBlank()) {
    throw PrintMessage(
        "Spec is missing targetServiceAccount (see 'workload admin profiles show <id> --json').",
        statusCode = 1,
        printError = true,
    )
  }
  return spec
}

/** Reports what a create/update produced — including the digest the backend actually pinned. */
private fun CliktCommand.echoRevisionResult(
    verb: String,
    profileId: String,
    revision: AdminProfileRevision,
) {
  val lines = mutableListOf("$verb $profileId (revision ${revision.revision})")
  lines.add("  verification: ${shortEnum(revision.verificationStatus)}")
  if (revision.dockerImage.isNotBlank()) {
    lines.add("  image: ${shortEnum(revision.imageStatus)}")
    if (revision.dockerImageDigest.isNotBlank())
        lines.add("  pinned: ${revision.dockerImageDigest}")
  }
  echo(lines.joinToString("\n"))
}

// ---------------------------------------------------------------------------
// admin (root group) + session
// ---------------------------------------------------------------------------

/**
 * Admin-plane operations — profile management, driven by a human Google sign-in (the operator's own
 * Workspace account), not a worker registration. A sibling to the `worker` group.
 */
class AdminCommand : NoOpCliktCommand(name = "admin") {
  override fun help(context: Context) =
      "Admin operations: profile and worker management, authenticated with your Google sign-in."
}

/** `admin login` — the loopback + PKCE browser sign-in; caches the refresh token for later. */
class AdminLoginCommand : CliktCommand(name = "login") {
  private val env by requireEnvironment()

  override fun help(context: Context) =
      "Sign in with your medusa.software Google account and cache the session."

  override fun run() {
    val secret =
        env.oauthClientSecret
            ?: throw PrintMessage(
                "This CLI build has no admin OAuth client secret for ${env.label} and " +
                    "${env.oauthClientSecretEnvVar} is not set. Install a released build, or set " +
                    "that env var for a local build.",
                statusCode = 1,
                printError = true,
            )

    val tokens =
        try {
          AdminOAuth(clientId = env.oauthClientId, clientSecret = secret).login(echo = { echo(it) })
        } catch (e: AdminOAuthException) {
          throw PrintMessage("Sign-in failed: ${e.message}", statusCode = 1, printError = true)
        }

    val refreshToken =
        tokens.refreshToken
            ?: throw PrintMessage(
                "Google did not return a refresh token, so the session can't be cached. Try again.",
                statusCode = 1,
                printError = true,
            )
    val email = AdminJwt.email(tokens.idToken) ?: "unknown"
    saveAdminCredentials(
        AdminCredentials(refreshToken, tokens.idToken, tokens.expiresAtEpochSec, email),
        env.configDir,
    )
    echo("Signed in as $email")
  }
}

/** `admin logout` — forget the cached session. */
class AdminLogoutCommand : CliktCommand(name = "logout") {
  private val env by requireEnvironment()

  override fun help(context: Context) = "Forget the cached admin session on this machine."

  override fun run() {
    deleteAdminCredentials(env.configDir)
    echo("Signed out.")
  }
}

// ---------------------------------------------------------------------------
// admin profiles ...
// ---------------------------------------------------------------------------

class AdminProfilesCommand : NoOpCliktCommand(name = "profiles") {
  override fun help(context: Context) = "Inspect and manage profiles."
}

class AdminProfilesListCommand : AdminActionCommand(name = "list") {
  override fun help(context: Context) = "List all profiles, with a live-runs count per profile."

  override fun run() {
    val c = client()
    echo(runAdmin { formatProfileTable(c.listProfiles(), c.listRuns(liveOnly = true)) })
  }
}

class AdminProfilesShowCommand : AdminActionCommand(name = "show") {
  private val profileId by argument(name = "profile-id")
  private val revisionNumber by
      option("--revision", "-r", help = "Show a specific revision (default: latest)").int()
  private val asJson by
      option("--json", help = "Emit the create/update spec for the selected revision as JSON")
          .flag()

  override fun help(context: Context) = "Show a profile's revision detail (latest by default)."

  override fun run() {
    val revisions = runAdmin { client().listProfileRevisions(profileId) }.sortedBy { it.revision }
    if (revisions.isEmpty()) {
      throw PrintMessage(
          "No such profile, or it has no revisions: $profileId",
          statusCode = 1,
          printError = true,
      )
    }

    val selected =
        if (revisionNumber != null) {
          revisions.find { it.revision == revisionNumber }
              ?: throw PrintMessage(
                  "Profile $profileId has no revision $revisionNumber.",
                  statusCode = 1,
                  printError = true,
              )
        } else {
          revisions.last()
        }

    if (asJson) {
      echo(specJson.encodeToString(specFromRevision(selected)))
    } else {
      echo(
          formatRevisionDetail(
              selected,
              isLatest = selected == revisions.last(),
              total = revisions.size,
          )
      )
    }
  }
}

class AdminProfilesCreateCommand : AdminActionCommand(name = "create") {
  private val profileId by argument(name = "profile-id")
  private val displayName by
      option("--display-name", help = "Human-friendly name (defaults to the profile id).")
  private val file by
      option("-f", "--file", help = "Revision spec JSON file, or - for stdin.").required()

  override fun help(context: Context) =
      "Create a profile from a JSON revision spec (the shape 'profiles show --json' emits)."

  override fun run() {
    val spec = readSpec(file) { System.`in`.readBytes().toString(Charsets.UTF_8) }
    val revision = runAdmin { client().createProfile(profileId, displayName ?: profileId, spec) }
    echoRevisionResult("Created", profileId, revision)
  }
}

class AdminProfilesUpdateCommand : AdminActionCommand(name = "update") {
  private val profileId by argument(name = "profile-id")
  private val file by
      option("-f", "--file", help = "Revision spec JSON file, or - for stdin.").required()

  override fun help(context: Context) = "Add a new revision to a profile from a JSON revision spec."

  override fun run() {
    val spec = readSpec(file) { System.`in`.readBytes().toString(Charsets.UTF_8) }
    val revision = runAdmin { client().updateProfile(profileId, spec) }
    echoRevisionResult("Updated", profileId, revision)
  }
}

class AdminProfilesVerifyCommand : AdminActionCommand(name = "verify") {
  private val profileId by argument(name = "profile-id")

  override fun help(context: Context) = "Re-verify a profile's latest revision against live GCP."

  override fun run() {
    val revision = runAdmin { client().verifyProfile(profileId) }
    val image =
        if (revision.dockerImage.isNotBlank()) ", image ${shortEnum(revision.imageStatus)}" else ""
    echo(
        "Verified $profileId (revision ${revision.revision}): " +
            "${shortEnum(revision.verificationStatus)}$image"
    )
  }
}

class AdminProfilesArchiveCommand : AdminActionCommand(name = "archive") {
  private val profileId by argument(name = "profile-id")

  override fun help(context: Context) = "Archive a profile (no longer grantable)."

  override fun run() {
    val profile = runAdmin { client().archiveProfile(profileId) }
    echo("Archived ${profile.profileId}.")
  }
}

class AdminProfilesGrantCommand : AdminActionCommand(name = "grant") {
  private val profileId by argument(name = "profile-id")
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Grant a profile to a worker."

  override fun run() {
    runAdmin { client().grantProfile(workerId, profileId) }
    echo("Granted $profileId to $workerId.")
  }
}

class AdminProfilesRevokeGrantCommand : AdminActionCommand(name = "revoke") {
  private val profileId by argument(name = "profile-id")
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Revoke a worker's grant of a profile."

  override fun run() {
    runAdmin { client().revokeProfileGrant(workerId, profileId) }
    echo("Revoked $profileId from $workerId.")
  }
}

// ---------------------------------------------------------------------------
// admin workers ...
// ---------------------------------------------------------------------------

class AdminWorkersCommand : NoOpCliktCommand(name = "workers") {
  override fun help(context: Context) = "Inspect and manage worker registrations."
}

class AdminWorkersListCommand : AdminActionCommand(name = "list") {
  private val includeRevoked by
      option(
              "--include-revoked",
              help = "Also show revoked workers (hidden by default), with when they were revoked.",
          )
          .flag()

  override fun help(context: Context) =
      "List workers, grouped by state, with per-worker activity from live runs."

  override fun run() {
    val c = client()
    echo(
        runAdmin {
          formatWorkerSections(c.listWorkers(), c.listRuns(liveOnly = true), includeRevoked)
        }
    )
  }
}

class AdminWorkersApproveCommand : AdminActionCommand(name = "approve") {
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Approve a pending worker."

  override fun run() = echoWorker(runAdmin { client().approveWorker(workerId) }, "Approved")
}

class AdminWorkersRejectCommand : AdminActionCommand(name = "reject") {
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Reject a pending worker."

  override fun run() = echoWorker(runAdmin { client().rejectWorker(workerId) }, "Rejected")
}

class AdminWorkersRevokeCommand : AdminActionCommand(name = "revoke") {
  private val workerId by argument(name = "worker-id")
  private val force by
      option(
              "--force",
              "-f",
              help = "Revoke even when the worker has live runs, without confirming.",
          )
          .flag()

  override fun help(context: Context) = "Revoke an active worker's access."

  override fun run() {
    val client = client()
    // A revoked worker's heartbeats stop authenticating, so its running runs derive `lost`. Warn
    // (and confirm) before doing that — the CLI twin of the console's revoke dialog.
    val running =
        runAdmin { client.listRuns(workerId = workerId, liveOnly = true) }
            .count { it.state == runStateRunning }
    revokeWarning(workerId, running)?.let { warning ->
      if (!force) {
        echo(warning, err = true)
        if (!confirmRevoke()) {
          throw PrintMessage(
              "Aborted. Re-run with --force to revoke despite the live run(s).",
              statusCode = 1,
              printError = true,
          )
        }
      }
    }
    echoWorker(runAdmin { client.revokeWorker(workerId) }, "Revoked")
  }

  // Interactive y/N when attached to a terminal; otherwise (piped/CI) refuse without --force.
  private fun confirmRevoke(): Boolean {
    val console = System.console() ?: return false
    val answer = console.readLine("Revoke anyway? [y/N] ")?.trim()?.lowercase()
    return answer == "y" || answer == "yes"
  }
}

/** The revoke live-runs warning, or null when the worker has none. Pure — unit-tested. */
internal fun revokeWarning(worker: String, runningCount: Int): String? =
    if (runningCount <= 0) null
    else "Revoking $worker will kill $runningCount live run(s) — they'll fail and derive as lost."

private fun CliktCommand.echoWorker(worker: AdminWorker, verb: String) {
  echo("$verb ${worker.name.ifBlank { worker.workerId }} — status: ${shortEnum(worker.status)}")
}

// ---------------------------------------------------------------------------
// enrollment
// ---------------------------------------------------------------------------

class AdminEnrollmentCommand : NoOpCliktCommand(name = "enrollment") {
  override fun help(context: Context) =
      "Mint and manage one-time worker enrollment tokens (the M4 v2 registration front door)."
}

class AdminEnrollmentCreateCommand : AdminActionCommand(name = "create") {
  private val note by
      option("--note", help = "Free-text label recorded with the token, e.g. \"for Kuba's MBP\".")
  private val expiresInDays by
      option("--expires-in-days", help = "Days until the token expires (server default: 7).").int()
  private val requireApproval by
      option(
              "--require-approval",
              help = "Land the worker PENDING for admin approval instead of straight to ACTIVE.",
          )
          .flag()

  override fun help(context: Context) =
      "Mint a one-time wle_ enrollment token. The token is printed once to stdout — hand it to the " +
          "teammate, who runs 'workload worker register' with it. It cannot be retrieved again."

  override fun run() {
    val result = runAdmin {
      client().createEnrollmentToken(note ?: "", expiresInDays ?: 0, requireApproval)
    }
    // The token goes to stdout alone (so it can be piped/copied); everything else is context on
    // stderr — the same split as 'workload worker token'.
    echo(result.token)
    val token = result.enrollmentToken
    echo("Enrollment token: ${token.enrollmentTokenId}", err = true)
    echo("Expires:          ${formatTimestamp(token.expiresAt)}", err = true)
    echo(
        "On registration:  ${if (token.requireApproval) "worker lands PENDING (needs approval)" else "worker becomes ACTIVE immediately"}",
        err = true,
    )
    echo("Shown once — it is not retrievable again. Hand it to the teammate over chat.", err = true)
  }
}

class AdminEnrollmentListCommand : AdminActionCommand(name = "list") {
  override fun help(context: Context) =
      "List outstanding (unused, unexpired, unrevoked) enrollment tokens."

  override fun run() {
    echo(formatEnrollmentTokenTable(runAdmin { client().listEnrollmentTokens() }))
  }
}

class AdminEnrollmentRevokeCommand : AdminActionCommand(name = "revoke") {
  private val enrollmentTokenId by argument(name = "enrollment-token-id")

  override fun help(context: Context) =
      "Revoke an outstanding enrollment token so it can no longer be redeemed."

  override fun run() {
    runAdmin { client().revokeEnrollmentToken(enrollmentTokenId) }
    echo("Revoked enrollment token $enrollmentTokenId.")
  }
}

// ---------------------------------------------------------------------------
// runs
// ---------------------------------------------------------------------------

class AdminRunsCommand : NoOpCliktCommand(name = "runs") {
  override fun help(context: Context) =
      "Inspect runs — who is running what, right now and recently."
}

class AdminRunsListCommand : AdminActionCommand(name = "list") {
  private val profileId by
      option("--profile", "-p", help = "Only runs of this profile — 'who is online for X'.")
  private val workerId by option("--worker", "-w", help = "Only runs on this worker.")
  private val all by
      option("--all", help = "Include finished runs (default: only in-flight runs).").flag()
  private val watch by
      option(
              "--watch",
              help =
                  "Refresh continuously until interrupted (Ctrl-C) — watch runs go running → lost " +
                      "as heartbeats age out.",
          )
          .flag()
  private val intervalSeconds by
      option("--interval", help = "Seconds between refreshes in --watch mode (default 2).")
          .int()
          .default(2)

  override fun help(context: Context) =
      "List runs. By default only in-flight runs (running or lost); --all includes finished ones."

  override fun run() {
    val client = client()
    val fetch = {
      runAdmin { client.listRuns(profileId = profileId, workerId = workerId, liveOnly = !all) }
    }
    if (!watch) {
      echo(formatRunTable(fetch()))
      return
    }
    watchLoop(fetch)
  }

  /**
   * Polls and redraws until interrupted (mirrors the console's auto-refreshing runs page). A frame
   * is drawn straight to stdout — clearing the screen when attached to a terminal — so `lost`
   * appears in place as a run's heartbeat ages out. Fails fast if the very first fetch fails (bad
   * auth/URL); after that, a transient error is shown in the frame and polling continues.
   */
  private fun watchLoop(fetch: () -> List<AdminRun>) {
    val interactive = System.console() != null
    val step = intervalSeconds.coerceAtLeast(1)
    var first = true
    while (true) {
      val frame =
          try {
            formatRunTable(fetch())
          } catch (e: Exception) {
            if (first) throw e
            "runs unavailable this tick: ${e.message}"
          }
      first = false
      if (interactive) print("\u001B[2J\u001B[H")
      println("workload runs — ${env.label} — refreshing every ${step}s — Ctrl-C to stop")
      println(frame)
      System.out.flush()
      try {
        Thread.sleep(step * 1000L)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/** A fixed-width table with a header row; columns padded to their widest cell. */
internal fun renderTable(header: List<String>, rows: List<List<String>>): String {
  val widths = header.indices.map { c -> (rows.map { it[c].length } + header[c].length).max() }
  fun line(cells: List<String>) =
      cells.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd()
  return (listOf(line(header)) + rows.map { line(it) }).joinToString("\n")
}

/** A run is "running" for presence purposes only when its heartbeat is still fresh (not lost). */
private const val runStateRunning = "RUN_STATE_RUNNING"

/**
 * Worker names of the fresh-running runs per profile — the presence signal, keyed by profile id.
 */
private fun runningWorkersByProfile(liveRuns: List<AdminRun>): Map<String, List<String>> =
    liveRuns
        .filter { it.state == runStateRunning && it.profileId.isNotBlank() }
        .groupBy { it.profileId }
        .mapValues { (_, runs) -> runs.map { it.workerName.ifBlank { it.workerId } } }

/** Count of fresh-running runs per worker id — the "● N running" activity signal. */
private fun runningCountByWorker(liveRuns: List<AdminRun>): Map<String, Int> =
    liveRuns.filter { it.state == runStateRunning }.groupingBy { it.workerId }.eachCount()

/** `● 2 (tux-flow…, jakub-…)`, truncated to two names; `—` when idle. */
private fun activeRunsCell(workers: List<String>): String {
  if (workers.isEmpty()) return "—"
  val shown = workers.take(2).joinToString(", ")
  val suffix = if (workers.size > 2) ", …" else ""
  return "● ${workers.size} ($shown$suffix)"
}

internal fun formatProfileTable(profiles: List<AdminProfile>, liveRuns: List<AdminRun>): String {
  if (profiles.isEmpty()) return "No profiles."
  val running = runningWorkersByProfile(liveRuns)
  return renderTable(
      listOf("PROFILE ID", "LATEST REV", "STATUS", "ACTIVE RUNS", "CREATED"),
      profiles.map {
        listOf(
            it.profileId,
            it.latestRevision.toString(),
            if (it.archived) "archived" else "active",
            activeRunsCell(running[it.profileId].orEmpty()),
            formatTimestamp(it.createdAt),
        )
      },
  )
}

internal fun formatEnrollmentTokenTable(tokens: List<AdminEnrollmentToken>): String {
  if (tokens.isEmpty()) return "No outstanding enrollment tokens."
  return renderTable(
      listOf("TOKEN ID", "NOTE", "CREATED BY", "EXPIRES", "APPROVAL"),
      tokens.map {
        listOf(
            it.enrollmentTokenId,
            it.note.ifBlank { "-" },
            it.createdBy.ifBlank { "-" },
            formatTimestamp(it.expiresAt),
            if (it.requireApproval) "required" else "auto",
        )
      },
  )
}

private const val workerStatusPending = "WORKER_STATUS_PENDING"
private const val workerStatusActive = "WORKER_STATUS_ACTIVE"
private const val workerStatusRejected = "WORKER_STATUS_REJECTED"
private const val workerStatusRevoked = "WORKER_STATUS_REVOKED"
private const val runStateLost = "RUN_STATE_LOST"

/** A titled section: the header line, then the table indented two spaces under it. */
private fun section(title: String, header: List<String>, rows: List<List<String>>): String =
    title + "\n" + renderTable(header, rows).lines().joinToString("\n") { "  $it" }

/**
 * Workers grouped by lifecycle state, with per-worker activity from live runs. Revoked workers are
 * hidden unless [includeRevoked]; rejected registrations are hidden entirely (a footnote counts
 * them — surfacing their history is the deferred lifecycle-cleanup story). Pending workers get
 * their own section; active workers show an ACTIVITY column (`● N running` from fresh live runs,
 * else `idle`) plus last-seen.
 */
internal fun formatWorkerSections(
    workers: List<AdminWorker>,
    liveRuns: List<AdminRun>,
    includeRevoked: Boolean,
): String {
  if (workers.isEmpty()) return "No workers."
  val runningByWorker = runningCountByWorker(liveRuns)
  val pending = workers.filter { it.status == workerStatusPending }
  val active = workers.filter { it.status == workerStatusActive }
  val revoked = workers.filter { it.status == workerStatusRevoked }
  val rejectedCount = workers.count { it.status == workerStatusRejected }

  val sections = mutableListOf<String>()

  if (pending.isNotEmpty()) {
    sections +=
        section(
            "Pending approval:",
            listOf("WORKER ID", "NAME", "SOURCE IP", "CREATED"),
            pending.map {
              listOf(
                  it.workerId,
                  it.name.ifBlank { "—" },
                  it.sourceIp.ifBlank { "—" },
                  formatTimestamp(it.createdAt),
              )
            },
        )
  }

  if (active.isNotEmpty()) {
    sections +=
        section(
            "Workers:",
            listOf("WORKER ID", "NAME", "ACTIVITY", "GRANTS", "LAST SEEN", "CREATED"),
            active.map {
              val running = runningByWorker[it.workerId] ?: 0
              listOf(
                  it.workerId,
                  it.name.ifBlank { "—" },
                  if (running > 0) "● $running running" else "idle",
                  if (it.grantedProfileIds.isEmpty()) "—"
                  else it.grantedProfileIds.joinToString(","),
                  formatRelative(it.lastSeenAt),
                  formatTimestamp(it.createdAt),
              )
            },
        )
  }

  if (includeRevoked && revoked.isNotEmpty()) {
    sections +=
        section(
            "Revoked workers:",
            listOf("WORKER ID", "NAME", "REVOKED", "CREATED"),
            revoked.map {
              listOf(
                  it.workerId,
                  it.name.ifBlank { "—" },
                  formatTimestamp(it.revokedAt),
                  formatTimestamp(it.createdAt),
              )
            },
        )
  }

  val notes = mutableListOf<String>()
  if (!includeRevoked && revoked.isNotEmpty()) {
    notes += "(${revoked.size} revoked worker(s) hidden — pass --include-revoked to show them.)"
  }
  if (rejectedCount > 0) {
    notes += "($rejectedCount rejected registration(s) hidden.)"
  }

  return (sections + notes).joinToString("\n\n").ifBlank { "No workers to show." }
}

/**
 * The runs table (M6-B2). `PROFILE@REV` pins what was claimed; DURATION is elapsed time (`—` for a
 * lost run, whose end is unknown); EXIT is shown only for a run that reported one.
 */
internal fun formatRunTable(runs: List<AdminRun>): String {
  if (runs.isEmpty()) return "No runs."
  return renderTable(
      listOf("RUN ID", "KIND", "PROFILE@REV", "WORKER", "STATE", "STARTED", "DURATION", "EXIT"),
      runs.map {
        listOf(
            it.runId,
            shortRunEnum(it.kind),
            if (it.profileId.isBlank()) "—" else "${it.profileId}@${it.revision}",
            it.workerName.ifBlank { it.workerId },
            shortRunEnum(it.state),
            formatTimestamp(it.startedAt),
            if (it.state == runStateLost) "—" else formatRunDuration(it.startedAt, it.endedAt),
            if (it.hasExitCode) it.exitCode.toString() else "—",
        )
      },
  )
}

internal fun formatRevisionDetail(
    revision: AdminProfileRevision,
    isLatest: Boolean,
    total: Int,
): String {
  val lines = mutableListOf<String>()
  fun field(label: String, value: String) = lines.add("${label.padEnd(14)}$value")

  field("Profile", revision.profileId)
  field("Revision", "${revision.revision} of $total${if (isLatest) " (latest)" else ""}")
  field("Target SA", revision.targetServiceAccount)
  field("Verification", shortEnum(revision.verificationStatus))
  if (revision.dockerImage.isBlank()) {
    field("Image", "— (pure exec/env profile)")
  } else {
    field("Image", revision.dockerImage)
    field("  status", shortEnum(revision.imageStatus))
    if (revision.dockerImageDigest.isNotBlank()) field("  pinned", revision.dockerImageDigest)
  }
  field("Note", revision.note.ifBlank { "—" })
  field(
      "Created",
      "${formatTimestamp(revision.createdAt)} by ${revision.createdBy.ifBlank { "—" }}",
  )

  lines.add("Env vars:")
  if (revision.envVars.isEmpty()) lines.add("  —")
  else revision.envVars.toSortedMap().forEach { (k, v) -> lines.add("  $k=$v") }

  lines.add("Secret env vars:")
  if (revision.secretEnvVars.isEmpty()) lines.add("  —")
  else revision.secretEnvVars.toSortedMap().forEach { (k, v) -> lines.add("  $k=$v") }

  return lines.joinToString("\n")
}
