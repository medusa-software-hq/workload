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

// Phase 2 of the automated-rollout epic (workload#126): a CI principal that only needs to push a
// worker digest (e.g. Flow's release automation) can be allow-listed here with a profile scope
// instead of full admin. Format: "email1=profile-a,profile-b;email2=profile-c" — an SA on
// ADMIN_SERVICE_ACCOUNTS but absent from this map is unrestricted (the pre-existing s2s admin).
private const val adminServiceAccountProfileScopesEnvVarName =
    "ADMIN_SERVICE_ACCOUNT_PROFILE_SCOPES"

// GCE node principal (M7): a worker-plane node identity, verified the same way as the s2s admin
// principal above — a Google-signed ID token naming this API's own URL as its audience — but
// against a *separate* allowlist, so an admin-allow-listed SA gains no worker-plane power (or vice
// versa). See WorkerNodeIdentityService and GooglePrincipalVerifier.
private const val gceNodeServiceAccountsEnvVarName = "GCE_NODE_SERVICE_ACCOUNTS"
private const val corsOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"
private const val databaseUrlEnvVarName = "DATABASE_URL"

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

  // The audience an s2s admin's ID token must name — this API's own URL. Optional: absent just
  // means
  // no service principal can authenticate (only humans).
  val serviceAudience = System.getenv(serviceAudienceEnvVarName)?.takeIf { it.isNotBlank() }

  // Allow-listed admin service accounts, comma-separated. Empty (or unset) means no SA is accepted.
  val adminServiceAccounts =
      System.getenv(adminServiceAccountsEnvVarName)
          ?.split(",")
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.toSet()
          .orEmpty()

  // Per-SA profile scope for the admin plane, ";"-separated entries of "email=id1,id2". Empty (or
  // unset) means every admin SA above stays unrestricted.
  val adminServiceAccountProfileScopes =
      System.getenv(adminServiceAccountProfileScopesEnvVarName)
          ?.split(";")
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.associate { entry ->
            val (email, ids) = entry.split("=", limit = 2)
            email.trim() to ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
          }
          .orEmpty()

  // Allow-listed GCE worker-plane node service accounts, comma-separated. Empty (or unset) means no
  // node identity is accepted — the secret-based worker plane is untouched either way.
  val gceNodeServiceAccounts =
      System.getenv(gceNodeServiceAccountsEnvVarName)
          ?.split(",")
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.toSet()
          .orEmpty()

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
  val workerNodeIdentityService =
      WorkerNodeIdentityService(
          GooglePrincipalVerifier(
              humanAudiences = emptySet(),
              allowedDomain = allowedDomain,
              serviceAudience = serviceAudience,
              serviceAccountAllowlist = gceNodeServiceAccounts,
          )
      )

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
                      serviceAccountProfileScopes = adminServiceAccountProfileScopes,
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
          workerNodeIdentityService = workerNodeIdentityService,
          workerAssignmentsService = WorkerAssignmentsService(fleetStore),
      )
      .start()
      .join()
}
