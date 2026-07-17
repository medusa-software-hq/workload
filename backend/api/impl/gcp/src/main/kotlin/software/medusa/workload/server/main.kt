package software.medusa.workload.server

import com.google.cloud.iam.credentials.v1.IamCredentialsClient

private const val portEnvVarName = "PORT"
private const val clientIdEnvVarName = "GOOGLE_CLIENT_ID"
private const val cliClientIdEnvVarName = "GOOGLE_CLI_CLIENT_ID"
private const val allowedDomainEnvVarName = "GOOGLE_ALLOWED_DOMAIN"
private const val corsOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"
private const val databaseUrlEnvVarName = "DATABASE_URL"

private const val workerApiPathPrefixSecretNameEnvVarName = "WORKER_API_PATH_PREFIX_SECRET_NAME"

private const val tokenLifetimeSecondsEnvVarName = "TOKEN_LIFETIME_SECONDS"

fun main() {
  val port =
      System.getenv(portEnvVarName)?.toIntOrNull()
          ?: error("$portEnvVarName environment variable must be set to a valid integer")

  val clientId =
      System.getenv(clientIdEnvVarName)
          ?: error("$clientIdEnvVarName environment variable must be set")

  // Optional — the `workload admin` CLI's Desktop OAuth client. When set, tokens minted by that
  // client are accepted alongside the SPA's. Absent (e.g. a variant without the CLI) just means
  // only the SPA can reach the admin API.
  val cliClientId = System.getenv(cliClientIdEnvVarName)?.takeIf { it.isNotBlank() }

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

  // One physical database, shared by every Postgres-backed store — a single connection pool and
  // a single Flyway migration run.
  val database = buildPostgresWorkloadDatabase(databaseUrl)
  val fleetStore = PostgresFleetStore(database)

  // Shared by the token broker and profile-verification dry-run mints — both are
  // GenerateAccessToken calls against the same broker runtime SA, no reason to open two clients.
  val iamCredentialsClient = IamCredentialsClient.create()

  val tokenLifetimeSeconds = System.getenv(tokenLifetimeSecondsEnvVarName)?.toLongOrNull() ?: 900L
  val tokenMinter = IamTokenMinter(iamCredentialsClient)
  val workerTokenBroker =
      WorkerTokenBrokerService(
          fleetStore = fleetStore,
          tokenMinter = tokenMinter,
          tokenLifetimeSeconds = tokenLifetimeSeconds,
      )
  val workerClaimService =
      WorkerClaimService(
          fleetStore = fleetStore,
          tokenMinter = tokenMinter,
          tokenLifetimeSeconds = tokenLifetimeSeconds,
      )

  buildServer(
          originRegex = corsOriginRegex,
          port = port,
          workerApiPathPrefix = workerApiPathPrefix,
          auth = GoogleIdTokenAuthDecorator(setOfNotNull(clientId, cliClientId), allowedDomain),
          counterStore = PostgresWorkloadStore(database),
          fleetStore = fleetStore,
          impersonationVerifier = IamImpersonationVerifier(iamCredentialsClient),
          imageDigestResolver = GcpImageDigestResolver(iamCredentialsClient),
          workerTokenBroker = workerTokenBroker,
          workerClaimService = workerClaimService,
          registrationService = RegistrationService(fleetStore),
          selfStatusService = SelfStatusService(fleetStore),
      )
      .start()
      .join()
}
