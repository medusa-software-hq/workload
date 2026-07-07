package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main

class MainCommand : CliktCommand() {
  override fun run() {
    echo("Hello, world!")
  }
}

fun main(
    args: Array<String>,
) {
  MainCommand().main(args)
}
