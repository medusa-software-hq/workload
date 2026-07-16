package software.medusa.workload.docker

/**
 * The one exception hierarchy the connector throws. Every failure — transport, daemon-side, or
 * malformed response — surfaces as one of these, with an actionable message and never a stack trace
 * printed by the library or any thread it owns (a hard M3-01 requirement; see the design doc's
 * Transport section and Drydock findings §B1).
 */
sealed class DockerConnectorException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The daemon couldn't be reached at all: socket missing, connection refused (daemon down),
 * permission denied, or a mid-request transport drop. [message] is phrased for a CLI user.
 */
class DockerConnectionException(message: String, cause: Throwable? = null) :
    DockerConnectorException(message, cause)

/**
 * The daemon was reached and replied with a non-success HTTP status. [statusCode] is the HTTP
 * status; [apiMessage] is the daemon's own `{"message": ...}` text when present (else a fallback).
 */
class DockerApiException(
    val statusCode: Int,
    val apiMessage: String,
) : DockerConnectorException("Docker daemon returned $statusCode: $apiMessage")

/**
 * The daemon replied, but not in a shape we could parse (unexpected body, missing expected fields,
 * or a version too old to speak to). Distinct from [DockerApiException] so callers can tell "the
 * daemon said no" from "we couldn't understand the daemon".
 */
class DockerProtocolException(message: String, cause: Throwable? = null) :
    DockerConnectorException(message, cause)
