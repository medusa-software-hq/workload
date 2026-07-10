package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.StorageOptions
import java.time.Instant
import java.util.Date

// Keep in sync with worker-mvp/infra/gcs.tf's `local.sample_object_name`.
private const val defaultSampleObjectName = "hello.txt"

class ReadObjectCommand : CliktCommand(name = "read-object") {
  private val profileId by
      option("--profile", "-p", help = "The profile to claim a token for").required()
  private val bucket by option("--bucket", envvar = "WORKER_MVP_SAMPLE_BUCKET").required()
  private val objectName by
      option("--object", envvar = "WORKER_MVP_SAMPLE_OBJECT").default(defaultSampleObjectName)

  override fun run() {
    val config = loadConfigOrFail()
    val tokenResponse =
        try {
          claimToken(config.brokerBaseUrl, config.workerId, config.workerSecret, profileId)
        } catch (e: WorkerApiException) {
          throw PrintMessage(
              tokenClaimErrorMessage(e, profileId),
              statusCode = 1,
              printError = true,
          )
        }

    val credentials =
        GoogleCredentials.create(
            AccessToken(
                tokenResponse.accessToken,
                Date.from(Instant.parse(tokenResponse.expiresAt)),
            )
        )

    val storage = StorageOptions.newBuilder().setCredentials(credentials).build().service
    val bytes = storage.readAllBytes(bucket, objectName)

    echo("Object contents:")
    echo(String(bytes, Charsets.UTF_8))
  }
}
