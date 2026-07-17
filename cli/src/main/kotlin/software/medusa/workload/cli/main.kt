package software.medusa.workload.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class MainCommand : NoOpCliktCommand()

fun main(
    args: Array<String>,
) {
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
