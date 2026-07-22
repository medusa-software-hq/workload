package software.medusa.workload.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import kotlin.system.exitProcess

class MainCommand : NoOpCliktCommand()

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

  // Move any pre-M5 loose config into prod/ before commands touch on-disk state.
  migrateLegacyConfig()

  // Non-prod sessions announce themselves on stderr (state dirs prevent *state* mixing; this
  // prevents *human* mixing). Dim when stderr is a terminal; plain otherwise.
  environment.marker?.let { marker ->
    val styled = dimmedForStderr(marker)
    System.err.println(styled)
  }

  try {
    run(environment, args)
  } catch (e: BrokerUnreachableException) {
    // A failure to even reach the broker (bad URL, DNS, offline) — print the reason, not a stack
    // trace. Everything else is a bug and should surface loudly.
    System.err.println(e.message)
    exitProcess(1)
  }
}

private fun run(environment: Environment, args: Array<String>) {
  MainCommand()
      .context { obj = environment }
      .subcommands(
          WorkerCommand()
              .subcommands(
                  TokenCommand(),
                  ExecCommand(),
                  RunCommand(),
                  PsCommand(),
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
              ),
      )
      .main(args)
}
