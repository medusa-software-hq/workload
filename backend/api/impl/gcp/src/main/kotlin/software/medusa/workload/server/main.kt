package software.medusa.workload.server

import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import com.linecorp.armeria.server.HttpService

private const val portEnvVarName = "PORT"
private const val clientIdEnvVarName = "GOOGLE_CLIENT_ID"
private const val allowedDomainEnvVarName = "GOOGLE_ALLOWED_DOMAIN"
private const val corsOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"
private const val databaseUrlEnvVarName = "DATABASE_URL"

private const val workerApiPathPrefixSecretNameEnvVarName = "WORKER_API_PATH_PREFIX_SECRET_NAME"

private const val workerTokenBrokerEnabledEnvVarName = "WORKER_TOKEN_BROKER_ENABLED"
private const val targetServiceAccountEmailEnvVarName = "TARGET_SERVICE_ACCOUNT_EMAIL"
private const val bootstrapTokenSecretNameEnvVarName = "BOOTSTRAP_TOKEN_SECRET_NAME"
private const val tokenLifetimeSecondsEnvVarName = "TOKEN_LIFETIME_SECONDS"

fun main() {
  val port =
      System.getenv(portEnvVarName)?.toIntOrNull()
          ?: error("$portEnvVarName environment variable must be set to a valid integer")

  val clientId =
      System.getenv(clientIdEnvVarName)
          ?: error("$clientIdEnvVarName environment variable must be set")

  val allowedDomain =
      System.getenv(allowedDomainEnvVarName)
          ?: error("$allowedDomainEnvVarName environment variable must be set")

  val corsOriginRegex =
      System.getenv(corsOriginRegexEnvVarName)
          ?: error("$corsOriginRegexEnvVarName environment variable must be set")

  val databaseUrl =
      System.getenv(databaseUrlEnvVarName)
          ?: error("$databaseUrlEnvVarName environment variable must be set")

  val workerApiPathPrefixSecretName =
      System.getenv(workerApiPathPrefixSecretNameEnvVarName)
          ?: error("$workerApiPathPrefixSecretNameEnvVarName environment variable must be set")
  val workerApiPathPrefix = loadSecretPayload(workerApiPathPrefixSecretName)

  val workerTokenBroker: HttpService? =
      if (System.getenv(workerTokenBrokerEnabledEnvVarName) == "true") {
        val targetServiceAccountEmail =
            System.getenv(targetServiceAccountEmailEnvVarName)
                ?: error("$targetServiceAccountEmailEnvVarName environment variable must be set")
        val bootstrapTokenSecretName =
            System.getenv(bootstrapTokenSecretNameEnvVarName)
                ?: error("$bootstrapTokenSecretNameEnvVarName environment variable must be set")
        val tokenLifetimeSeconds =
            System.getenv(tokenLifetimeSecondsEnvVarName)?.toLongOrNull() ?: 900L

        WorkerTokenBrokerService(
            config =
                WorkerTokenBrokerConfig(
                    bootstrapToken = loadSecretPayload(bootstrapTokenSecretName),
                    targetServiceAccountEmail = targetServiceAccountEmail,
                    tokenLifetimeSeconds = tokenLifetimeSeconds,
                ),
            iamCredentialsClient = IamCredentialsClient.create(),
        )
      } else {
        null
      }

  buildServer(
          originRegex = corsOriginRegex,
          port = port,
          workerApiPathPrefix = workerApiPathPrefix,
          auth = GoogleIdTokenAuthDecorator(clientId, allowedDomain),
          counterStore = PostgresWorkloadStore.build(databaseUrl),
          workerTokenBroker = workerTokenBroker,
      )
      .start()
      .join()
}
