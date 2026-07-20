package software.medusa.workload.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import kotlin.system.exitProcess

class MainCommand : NoOpCliktCommand()

fun main(
    args: Array<String>,
) {
  try {
    run(args)
  } catch (e: BrokerUnreachableException) {
    // A failure to even reach the broker (bad URL, DNS, offline) — print the reason, not a stack
    // trace. Everything else is a bug and should surface loudly.
    System.err.println(e.message)
    exitProcess(1)
  }
}

private fun run(args: Array<String>) {
  MainCommand()
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
              ),
      )
      .main(args)
}
