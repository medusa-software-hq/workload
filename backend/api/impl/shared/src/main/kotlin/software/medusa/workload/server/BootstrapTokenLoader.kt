package software.medusa.workload.server

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient

/**
 * Loads a Secret Manager secret's payload by its full resource name
 * (`projects/.../secrets/.../versions/latest`).
 */
fun loadSecretPayload(secretResourceName: String): String {
  SecretManagerServiceClient.create().use { client ->
    return client.accessSecretVersion(secretResourceName).payload.data.toStringUtf8()
  }
}
