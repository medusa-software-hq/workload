package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlin.system.exitProcess
import software.medusa.workload.runtime.BrokerUnreachableException

class MainCommand : CliktCommand(name = "workload") {
  private val verbose by
      option(
              "--verbose",
              "-v",
              help =
                  "Print extra diagnostic detail on stderr, including how each call authenticates.",
          )
          .flag()

  override fun help(context: Context) =
      "The workload CLI: register and run workloads (worker), and manage the fleet (admin)."

  // Runs before any subcommand — publishes --verbose into the shared context so subcommands can
  // read
  // it, and prints the resolved environment once when verbose.
  override fun run() {
    val cli = currentContext.findObject<CliContext>() ?: return
    cli.verbose = verbose
    if (verbose) {
      echo(
          "[verbose] environment: ${cli.environment.label} — api ${cli.environment.apiBaseUrl}",
          err = true,
      )
    }
  }
}

fun main(
    args: Array<String>,
) {
  // Read-once composition root: resolve the environment from WORKLOAD_ENVIRONMENT exactly here,
  // then
  // inject it via the Clikt context so no command reads that variable again.
  val environment =
      try {
        Environment.current()
      } catch (e: EnvironmentSelectionException) {
        System.err.println(e.message)
        exitProcess(2)
      }

  // Likewise resolve the default admin auth method from WORKLOAD_AUTH_METHOD exactly once.
  val authMethodDefault =
      try {
        AdminAuthMethod.fromEnv(System.getenv(AdminAuthMethod.ENV_VAR))
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
      }

  // Move any pre-M5 loose config into prod/ before commands touch on-disk state.
  migrateLegacyConfig()

  // Non-prod sessions announce themselves on stderr (state dirs prevent *state* mixing; this
  // prevents *human* mixing). Dim when stderr is a terminal; plain otherwise.
  environment.marker?.let { marker ->
    val styled = dimmedForStderr(marker)
    System.err.println(styled)
  }

  try {
    run(environment, authMethodDefault, args)
  } catch (e: BrokerUnreachableException) {
    // A failure to even reach the broker (bad URL, DNS, offline) — print the reason, not a stack
    // trace. Everything else is a bug and should surface loudly.
    System.err.println(e.message)
    exitProcess(1)
  }
}

private fun run(
    environment: Environment,
    authMethodDefault: AdminAuthMethod?,
    args: Array<String>,
) {
  // One Clikt context object for the whole tree: CliContext (environment + auth default + verbose).
  // Clikt's `obj` is single-keyed, so a single combined object is the only way both the environment
  // and the global flags are reachable everywhere; commands read the environment via
  // requireEnvironment() and the rest via requireObject<CliContext>().
  MainCommand()
      .context { obj = CliContext(environment, authMethodDefault) }
      .subcommands(
          WorkerCommand()
              .subcommands(
                  TokenCommand(),
                  ExecCommand(),
                  RunCommand(),
                  PsCommand(),
                  PruneCommand(),
                  RegisterCommand(),
                  StatusCommand(),
                  UnregisterCommand(),
              ),
          AdminCommand()
              .subcommands(
                  AdminLoginCommand(),
                  AdminLogoutCommand(),
                  AdminProfilesCommand()
                      .subcommands(
                          AdminProfilesListCommand(),
                          AdminProfilesShowCommand(),
                          AdminProfilesCreateCommand(),
                          AdminProfilesUpdateCommand(),
                          AdminProfilesVerifyCommand(),
                          AdminProfilesArchiveCommand(),
                          AdminProfilesGrantCommand(),
                          AdminProfilesRevokeGrantCommand(),
                      ),
                  AdminWorkersCommand()
                      .subcommands(
                          AdminWorkersListCommand(),
                          AdminWorkersApproveCommand(),
                          AdminWorkersRejectCommand(),
                          AdminWorkersRevokeCommand(),
                      ),
                  AdminEnrollmentCommand()
                      .subcommands(
                          AdminEnrollmentCreateCommand(),
                          AdminEnrollmentListCommand(),
                          AdminEnrollmentRevokeCommand(),
                      ),
                  AdminRunsCommand().subcommands(AdminRunsListCommand()),
              ),
          NodeCommand().subcommands(NodeEnrollCommand(), NodeCreateCommand()),
      )
      .main(args)
}
