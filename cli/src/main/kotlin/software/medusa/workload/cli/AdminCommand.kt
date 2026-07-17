package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

// ---------------------------------------------------------------------------
// Shared plumbing
// ---------------------------------------------------------------------------

private fun adminClient(): AdminApiClient {
  val session = AdminSession()
  return AdminApiClient(AdminConfig.apiBaseUrl, idTokenProvider = { session.currentIdToken() })
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
  override fun help(context: Context) =
      "Sign in with your medusa.software Google account and cache the session."

  override fun run() {
    val secret =
        AdminConfig.clientSecret
            ?: throw PrintMessage(
                "This CLI build has no admin OAuth client secret and ${AdminConfig.CLIENT_SECRET_ENV}" +
                    " is not set. Install a released build, or set that env var for a local build.",
                statusCode = 1,
                printError = true,
            )

    val tokens =
        try {
          AdminOAuth(clientSecret = secret).login(echo = { echo(it) })
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
        AdminCredentials(refreshToken, tokens.idToken, tokens.expiresAtEpochSec, email)
    )
    echo("Signed in as $email")
  }
}

/** `admin logout` — forget the cached session. */
class AdminLogoutCommand : CliktCommand(name = "logout") {
  override fun help(context: Context) = "Forget the cached admin session on this machine."

  override fun run() {
    deleteAdminCredentials()
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
  override fun help(context: Context) = "List all profiles."

  override fun run() {
    echo(formatProfileTable(runAdmin { adminClient().listProfiles() }))
  }
}

class AdminProfilesShowCommand : CliktCommand(name = "show") {
  private val profileId by argument(name = "profile-id")
  private val revisionNumber by
      option("--revision", "-r", help = "Show a specific revision (default: latest)").int()
  private val asJson by
      option("--json", help = "Emit the create/update spec for the selected revision as JSON")
          .flag()

  override fun help(context: Context) = "Show a profile's revision detail (latest by default)."

  override fun run() {
    val revisions =
        runAdmin { adminClient().listProfileRevisions(profileId) }.sortedBy { it.revision }
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

class AdminProfilesVerifyCommand : CliktCommand(name = "verify") {
  private val profileId by argument(name = "profile-id")

  override fun help(context: Context) = "Re-verify a profile's latest revision against live GCP."

  override fun run() {
    val revision = runAdmin { adminClient().verifyProfile(profileId) }
    val image =
        if (revision.dockerImage.isNotBlank()) ", image ${shortEnum(revision.imageStatus)}" else ""
    echo(
        "Verified $profileId (revision ${revision.revision}): " +
            "${shortEnum(revision.verificationStatus)}$image"
    )
  }
}

class AdminProfilesArchiveCommand : CliktCommand(name = "archive") {
  private val profileId by argument(name = "profile-id")

  override fun help(context: Context) = "Archive a profile (no longer grantable)."

  override fun run() {
    val profile = runAdmin { adminClient().archiveProfile(profileId) }
    echo("Archived ${profile.profileId}.")
  }
}

class AdminProfilesGrantCommand : CliktCommand(name = "grant") {
  private val profileId by argument(name = "profile-id")
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Grant a profile to a worker."

  override fun run() {
    runAdmin { adminClient().grantProfile(workerId, profileId) }
    echo("Granted $profileId to $workerId.")
  }
}

class AdminProfilesRevokeGrantCommand : CliktCommand(name = "revoke") {
  private val profileId by argument(name = "profile-id")
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Revoke a worker's grant of a profile."

  override fun run() {
    runAdmin { adminClient().revokeProfileGrant(workerId, profileId) }
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
  override fun help(context: Context) = "List all workers."

  override fun run() {
    echo(formatWorkerTable(runAdmin { adminClient().listWorkers() }))
  }
}

class AdminWorkersApproveCommand : CliktCommand(name = "approve") {
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Approve a pending worker."

  override fun run() = echoWorker(runAdmin { adminClient().approveWorker(workerId) }, "Approved")
}

class AdminWorkersRejectCommand : CliktCommand(name = "reject") {
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Reject a pending worker."

  override fun run() = echoWorker(runAdmin { adminClient().rejectWorker(workerId) }, "Rejected")
}

class AdminWorkersRevokeCommand : CliktCommand(name = "revoke") {
  private val workerId by argument(name = "worker-id")

  override fun help(context: Context) = "Revoke an active worker's access."

  override fun run() = echoWorker(runAdmin { adminClient().revokeWorker(workerId) }, "Revoked")
}

private fun CliktCommand.echoWorker(worker: AdminWorker, verb: String) {
  echo("$verb ${worker.name.ifBlank { worker.workerId }} — status: ${shortEnum(worker.status)}")
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
