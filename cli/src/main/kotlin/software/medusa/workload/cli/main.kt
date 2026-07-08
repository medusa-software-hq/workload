package software.medusa.workload.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class MainCommand : NoOpCliktCommand()

fun main(
    args: Array<String>,
) {
  MainCommand().subcommands(TokenCommand(), ReadObjectCommand()).main(args)
}
