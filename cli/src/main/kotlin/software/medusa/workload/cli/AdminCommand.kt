package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.PrintMessage

/**
 * Admin-plane operations — profile management, driven by a human Google sign-in (the operator's own
 * Workspace account), not a worker registration. A sibling to the `worker` group.
 */
class AdminCommand : NoOpCliktCommand(name = "admin") {
  override fun help(context: Context) =
      "Admin operations: profile management, authenticated with your Google sign-in."
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

/** Parent for the profile subcommands. */
class AdminProfilesCommand : NoOpCliktCommand(name = "profiles") {
  override fun help(context: Context) = "Inspect and manage profiles."
}

/** `admin profiles list` — the first read-only proof of the whole admin loop. */
class AdminProfilesListCommand : CliktCommand(name = "list") {
  override fun help(context: Context) = "List all profiles."

  override fun run() {
    val session = AdminSession()
    val client =
        AdminApiClient(AdminConfig.apiBaseUrl, idTokenProvider = { session.currentIdToken() })

    val profiles =
        try {
          client.listProfiles()
        } catch (e: AdminNotLoggedInException) {
          throw PrintMessage(e.message ?: "Not signed in.", statusCode = 1, printError = true)
        } catch (e: AdminApiException) {
          throw PrintMessage(e.message ?: "Admin API error.", statusCode = 1, printError = true)
        }

    echo(formatProfileTable(profiles))
  }
}

/** Renders profiles as a fixed-width table (or a friendly note when there are none). */
internal fun formatProfileTable(profiles: List<AdminProfile>): String {
  if (profiles.isEmpty()) return "No profiles."

  val header = listOf("PROFILE ID", "LATEST REV", "STATUS", "CREATED")
  val rows = profiles.map { profile ->
    listOf(
        profile.profileId,
        profile.latestRevision.toString(),
        if (profile.archived) "archived" else "active",
        profile.createdAt,
    )
  }
  val widths =
      header.indices.map { column ->
        (rows.map { it[column].length } + header[column].length).max()
      }
  fun format(cells: List<String>) =
      cells.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd()

  return (listOf(format(header)) + rows.map { format(it) }).joinToString("\n")
}
