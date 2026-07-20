package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val adminCredentialsFileName = "admin.json"

private val adminJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
}

/**
 * Cached admin sign-in: the long-lived refresh token plus the most recent ID token and its expiry.
 * The refresh token is the sensitive bit — it stands in for the human until revoked — so this is
 * written 0600 like [WorkloadConfig].
 */
@Serializable
data class AdminCredentials(
    val refreshToken: String,
    val idToken: String,
    val idTokenExpiresAtEpochSec: Long,
    val email: String,
)

fun adminCredentialsFile(dir: Path = configDir()): Path = dir.resolve(adminCredentialsFileName)

fun loadAdminCredentials(dir: Path = configDir()): AdminCredentials? {
  val file = adminCredentialsFile(dir)
  if (!Files.exists(file)) return null
  return adminJson.decodeFromString(Files.readString(file))
}

/** Writes [credentials] with dir 0700 / file 0600, set atomically at creation where supported. */
fun saveAdminCredentials(credentials: AdminCredentials, dir: Path = configDir()) {
  if (!Files.exists(dir)) {
    runCatching {
          Files.createDirectory(
              dir,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
          )
        }
        .getOrElse { Files.createDirectories(dir) }
  }

  val file = adminCredentialsFile(dir)
  Files.deleteIfExists(file)
  runCatching {
        Files.createFile(
            file,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
      }
      .getOrElse { Files.createFile(file) }
  Files.writeString(file, adminJson.encodeToString(credentials))
}

fun deleteAdminCredentials(dir: Path = configDir()) {
  Files.deleteIfExists(adminCredentialsFile(dir))
}
