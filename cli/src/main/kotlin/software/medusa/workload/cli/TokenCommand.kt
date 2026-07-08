package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class TokenCommand : CliktCommand(name = "token") {
  private val brokerUrl by option("--broker-url", envvar = "WORKER_BROKER_URL").required()
  private val bootstrapToken by
      option("--bootstrap-token", envvar = "WORKER_MVP_BOOTSTRAP_TOKEN").required()

  override fun run() {
    val token = fetchBrokerToken(brokerUrl, bootstrapToken)
    echo("Access token: ${token.accessToken}")
    echo("Expires at:   ${token.expiresAt}")
    echo("Service acct: ${token.serviceAccount}")
  }
}
