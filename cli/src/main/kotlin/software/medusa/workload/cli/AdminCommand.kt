package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

// ---------------------------------------------------------------------------
// Shared plumbing
// ---------------------------------------------------------------------------

private fun adminClient(env: Environment): AdminApiClient {
  val session = AdminSession(dir = env.configDir, refresher = defaultRefresher(env))
  return AdminApiClient(env.apiBaseUrl, idTokenProvider = { session.currentIdToken() })
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
  private val env by requireObject<Environment>()

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
  private val env by requireObject<Environment>()

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

class AdminProfilesListCommand : CliktCommand(name = "list") {
  private val env by requireObject<Environment>()

  override fun help(context: Context) = "List all profiles."

  override fun run() {
    echo(formatProfileTable(runAdmin { adminClient(env).listProfiles() }))
  }
}

class AdminProfilesShowCommand : CliktCommand(name = "show") {
  private val env by requireObject<Environment>()
  private val profileId by argument(name = "profile-id")
  private val revisionNumber by
      option("--revision", "-r", help = "Show a specific revision (default: latest)").int()
  private val asJson by
      option("--json", help = "Emit the create/update spec for the selected revision as JSON")
          .flag()

  override fun help(context: Context) = "Show a profile's revision detail (latest by default)."

  override fun run() {
    val revisions =
        runAdmin { adminClient(env).listProfileRevisions(profileId) }.sortedBy { it.revision }
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

class AdminProfilesCreateCommand : CliktCommand(name = "create") {
  private val env by requireObject<Environment>()
  private val profileId by argument(name = "profile-id")
  private val displayName by
      option("--display-name", help = "Human-friendly name (defaults to the profile id).")
  private val file by
      option("-f", "--file", help = "Revision spec JSON file, or - for stdin.").required()

  override fun help(context: Context) =
      "Create a profile from a JSON revision spec (the shape 'profiles show --json' emits)."

  override fun run() {
    val spec = readSpec(file) { System.`in`.readBytes().toString(Charsets.UTF_8) }
    val revision = runAdmin {
      adminClient(env).createProfile(profileId, displayName ?: profileId, spec)
    }
    echoRevisionResult("Created", profileId, revision)
  }
}

class AdminProfilesUpdateCommand : CliktCommand(name = "update") {
  private val env by requireObject<Environment>()
  private val profileId by argument(name = "profile-id")
  private val file by
      option("-f", "--file", help = "Revision spec JSON file, or - for stdin.").required()

  override fun help(context: Context) = "Add a new revision to a profile from a JSON revision spec."

  override fun run() {
    val spec = readSpec(file) { System.`in`.readBytes().toString(Charsets.UTF_8) }
    val revision = runAdmin { adminClient(env).updateProfile(profileId, spec) }
    echoRevisionResult("Updated", profileId, revision)
  }
}

class AdminProfilesVerifyCommand : CliktCommand(name = "verify") {
  private val env by requireObject<Environment>()
  private val profileId by argument(name = "profile-id")

  override fun help(context: Context) = "Re-verify a profile's latest revision against live GCP."

  override fun run() {
    val revision = runAdmin { adminClient(env).verifyProfile(profileId) }
    val image =
        if (revision.dockerImage.isNotBlank()) ", image ${shortEnum(revision.imageStatus)}" else ""
    echo(
        "Verified $profileId (revision ${revision.revision}): " +
            "${shortEnum(revision.verificationStatus)}$image"
    )
  }
}

class AdminProfilesArchiveCommand : CliktCommand(name = "archive") {
  private val env by requireObject<Environment>()
  private val profileId by argument(name = "profile-id")

  override fun help(context: Context) = "Archive a profile (no longer grantable)."

  override fun run() {
    val profile = runAdmin { adminClient(env).archiveProfile(profileId) }
    echo("Archived ${profile.profileId}.")
  }
}

class AdminProfilesGrantCommand : CliktCommand(name = "grant") {
  private val env by requireObject<Environment>()
  private val profileId by argument(name = "profile-id")
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Grant a profile to a worker."

  override fun run() {
    runAdmin { adminClient(env).grantProfile(workerId, profileId) }
    echo("Granted $profileId to $workerId.")
  }
}

class AdminProfilesRevokeGrantCommand : CliktCommand(name = "revoke") {
  private val env by requireObject<Environment>()
  private val profileId by argument(name = "profile-id")
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Revoke a worker's grant of a profile."

  override fun run() {
    runAdmin { adminClient(env).revokeProfileGrant(workerId, profileId) }
    echo("Revoked $profileId from $workerId.")
  }
}

// ---------------------------------------------------------------------------
// admin workers ...
// ---------------------------------------------------------------------------

class AdminWorkersCommand : NoOpCliktCommand(name = "workers") {
  override fun help(context: Context) = "Inspect and manage worker registrations."
}

class AdminWorkersListCommand : CliktCommand(name = "list") {
  private val env by requireObject<Environment>()

  override fun help(context: Context) = "List all workers."

  override fun run() {
    echo(formatWorkerTable(runAdmin { adminClient(env).listWorkers() }))
  }
}

class AdminWorkersApproveCommand : CliktCommand(name = "approve") {
  private val env by requireObject<Environment>()
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Approve a pending worker."

  override fun run() = echoWorker(runAdmin { adminClient(env).approveWorker(workerId) }, "Approved")
}

class AdminWorkersRejectCommand : CliktCommand(name = "reject") {
  private val env by requireObject<Environment>()
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Reject a pending worker."

  override fun run() = echoWorker(runAdmin { adminClient(env).rejectWorker(workerId) }, "Rejected")
}

class AdminWorkersRevokeCommand : CliktCommand(name = "revoke") {
  private val env by requireObject<Environment>()
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Revoke an active worker's access."

  override fun run() = echoWorker(runAdmin { adminClient(env).revokeWorker(workerId) }, "Revoked")
}

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

class AdminEnrollmentCreateCommand : CliktCommand(name = "create") {
  private val env by requireObject<Environment>()
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
      adminClient(env).createEnrollmentToken(note ?: "", expiresInDays ?: 0, requireApproval)
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

class AdminEnrollmentListCommand : CliktCommand(name = "list") {
  private val env by requireObject<Environment>()

  override fun help(context: Context) =
      "List outstanding (unused, unexpired, unrevoked) enrollment tokens."

  override fun run() {
    echo(formatEnrollmentTokenTable(runAdmin { adminClient(env).listEnrollmentTokens() }))
  }
}

class AdminEnrollmentRevokeCommand : CliktCommand(name = "revoke") {
  private val env by requireObject<Environment>()
  private val enrollmentTokenId by argument(name = "enrollment-token-id")

  override fun help(context: Context) =
      "Revoke an outstanding enrollment token so it can no longer be redeemed."

  override fun run() {
    runAdmin { adminClient(env).revokeEnrollmentToken(enrollmentTokenId) }
    echo("Revoked enrollment token $enrollmentTokenId.")
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

internal fun formatProfileTable(profiles: List<AdminProfile>): String {
  if (profiles.isEmpty()) return "No profiles."
  return renderTable(
      listOf("PROFILE ID", "LATEST REV", "STATUS", "CREATED"),
      profiles.map {
        listOf(
            it.profileId,
            it.latestRevision.toString(),
            if (it.archived) "archived" else "active",
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

internal fun formatWorkerTable(workers: List<AdminWorker>): String {
  if (workers.isEmpty()) return "No workers."
  return renderTable(
      listOf("WORKER ID", "NAME", "STATUS", "GRANTS", "CREATED"),
      workers.map {
        listOf(
            it.workerId,
            it.name.ifBlank { "—" },
            shortEnum(it.status),
            if (it.grantedProfileIds.isEmpty()) "—" else it.grantedProfileIds.joinToString(","),
            formatTimestamp(it.createdAt),
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
