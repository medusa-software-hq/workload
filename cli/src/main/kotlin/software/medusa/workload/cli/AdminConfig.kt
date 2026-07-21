package software.medusa.workload.cli

/**
 * OAuth client + API endpoint the `admin` commands use. The API base URL lives in [BuildConfig] now
 * (one backend, shared with the worker plane). The client id is public and lives in source; the
 * client secret (which, for a Desktop OAuth client, Google explicitly does not treat as
 * confidential) is baked into the fat jar at build time from a generated resource — the Publish CLI
 * workflow passes it as `-PadminOauthClientSecret` from an Actions secret, so it isn't committed. A
 * local build has none baked in and falls back to the env var, so dev builds still work.
 */
object AdminConfig {
  /**
   * The workload-baseline Desktop OAuth client. Public; safe in source. Project-specific client in
   * the prod project (ms-workload-d91b0eaf); cut over from the sunset ms-auth client in M5-02. Kept
   * in sync with infra/common's prod `cli_client_id` (the API's accepted CLI audience). The matching
   * non-confidential Desktop secret is baked at publish from the `WORKLOAD_ADMIN_OAUTH_CLIENT_SECRET`
   * Actions secret, which holds *this* client's secret.
   */
  const val CLIENT_ID = "136908422577-jllms7h9l7gopqujps2d8e7jvtrc2qgb.apps.googleusercontent.com"

  const val CLIENT_SECRET_ENV = "WORKLOAD_ADMIN_OAUTH_CLIENT_SECRET"

  /**
   * The OAuth client secret, or null if this build has none baked in and no env override is set —
   * in which case `admin login` can't run and the caller reports how to fix it.
   */
  val clientSecret: String?
    get() =
        BuildConfig.resolve(
            System.getenv(CLIENT_SECRET_ENV),
            BuildConfig.bakedProperty("oauthClientSecret"),
            null,
        )

  /** The admin API base URL — the single backend endpoint (see [BuildConfig.apiBaseUrl]). */
  val apiBaseUrl: String
    get() = BuildConfig.apiBaseUrl
}
