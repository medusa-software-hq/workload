package software.medusa.workload.server

import com.google.cloud.iam.credentials.v1.IamCredentialsClient

private const val portEnvVarName = "PORT"
private const val clientIdEnvVarName = "GOOGLE_CLIENT_ID"
private const val cliClientIdEnvVarName = "GOOGLE_CLI_CLIENT_ID"
private const val allowedDomainEnvVarName = "GOOGLE_ALLOWED_DOMAIN"

// s2s admin principal (M5-05): the audience an allow-listed SA's ID token must name (this API's own
// URL) and the comma-separated list of SA emails permitted on the admin plane.
private const val serviceAudienceEnvVarName = "API_URL"
private const val adminServiceAccountsEnvVarName = "ADMIN_SERVICE_ACCOUNTS"
private const val corsOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"
private const val databaseUrlEnvVarName = "DATABASE_URL"

private const val tokenLifetimeSecondsEnvVarName = "TOKEN_LIFETIME_SECONDS"

/**
 * Parses [adminServiceAccountsEnvVarName]'s comma-separated value into an allowlist: each entry is
 * either a bare `email` (unrestricted admin, null scope) or `email=profile1|profile2` (restricted to
 * UpdateProfile on only those profile_ids). Blank/unset input yields an empty map — no SA accepted.
 */
internal fun parseAdminServiceAccountAllowlist(raw: String?): Map<String, Set<String>?> =
    raw
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.associate { entry ->
          val (email, scope) = entry.split("=", limit = 2).let { it[0] to it.getOrNull(1) }
          email to scope?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        }
        .orEmpty()

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

  // The audience an s2s admin's ID token must name — this API's own URL. Optional: absent just
  // means
  // no service principal can authenticate (only humans).
  val serviceAudience = System.getenv(serviceAudienceEnvVarName)?.takeIf { it.isNotBlank() }

  // Allow-listed admin service accounts, comma-separated. Empty (or unset) means no SA is accepted.
  // Each entry is either a bare email (unrestricted admin) or `email=profile1|profile2` (scoped to
  // UpdateProfile on only those profile_ids — workload#126 Phase 2's CI-push principal, e.g. a Flow
  // CI SA scoped to just `flow-worker`).
  val adminServiceAccounts = parseAdminServiceAccountAllowlist(System.getenv(adminServiceAccountsEnvVarName))

  val corsOriginRegex =
      System.getenv(corsOriginRegexEnvVarName)
          ?: error("$corsOriginRegexEnvVarName environment variable must be set")

  val databaseUrl =
      System.getenv(databaseUrlEnvVarName)
          ?: error("$databaseUrlEnvVarName environment variable must be set")

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
  val workerIdTokenBroker = WorkerIdTokenBrokerService(fleetStore, tokenMinter)

  buildServer(
          originRegex = corsOriginRegex,
          port = port,
          auth =
              GoogleIdTokenAuthDecorator(
                  GooglePrincipalVerifier(
                      humanAudiences = setOfNotNull(clientId, cliClientId),
                      allowedDomain = allowedDomain,
                      serviceAudience = serviceAudience,
                      serviceAccountAllowlist = adminServiceAccounts,
                  )
              ),
          fleetStore = fleetStore,
          impersonationVerifier = IamImpersonationVerifier(iamCredentialsClient),
          imageDigestResolver = GcpImageDigestResolver(iamCredentialsClient),
          workerTokenBroker = workerTokenBroker,
          workerIdTokenBroker = workerIdTokenBroker,
          workerClaimService = workerClaimService,
          selfStatusService = SelfStatusService(fleetStore),
          v2RegistrationService = RegistrationServiceV2(fleetStore),
          workerRunService = WorkerRunService(fleetStore),
      )
      .start()
      .join()
}
