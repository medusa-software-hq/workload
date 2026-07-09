package software.medusa.workload.server

import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.util.AttributeKey

private object AdminEmailAttrMarker

/** Set by whichever auth decorator ran (console auth in production, a fixed value locally). */
internal val adminEmailAttrKey: AttributeKey<String> =
    AttributeKey.valueOf(AdminEmailAttrMarker::class.java, "ADMIN_EMAIL")

/** The console admin's email for the request currently being served, for audit logging. */
internal fun currentAdminEmail(): String =
    ServiceRequestContext.current().attr(adminEmailAttrKey) ?: "unknown"

/** The caller's source IP for the request currently being served, for audit logging. */
internal fun currentSourceIp(): String =
    runCatching { ServiceRequestContext.current().clientAddress().hostAddress }
        .getOrDefault("unknown")
