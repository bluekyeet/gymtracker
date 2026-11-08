package com.gymtracker.server

import com.gymtracker.server.common.installRateLimit
import com.gymtracker.server.auth.authRoutes
import com.gymtracker.server.auth.AuthService
import com.gymtracker.server.sync.syncRoutes
import com.gymtracker.server.sync.SyncService
import com.gymtracker.server.database.DatabaseFactory
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.gymtracker.server.database.ServerMeta
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val minClientVersion: String,
    val serverId: String
)

@Serializable
data class EnrollRequest(
    val enrollmentKey: String,
    val deviceName: String,
    val deviceKind: String
)

@Serializable
data class EnrollResponse(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String
)

/**
 * Secrets and switches the server needs at startup. There are deliberately no built-in secrets:
 * this repository is public, so any default would be a known credential on every deployment that
 * forgot to override it. A missing or placeholder value stops the server instead.
 */
data class ServerConfig(
    val enrollmentKey: String,
    val jwtSecret: String,
    val enrollmentEnabled: Boolean = true
) {
    init {
        require(enrollmentKey.isNotBlank() && enrollmentKey !in KNOWN_PLACEHOLDERS) {
            "ENROLLMENT_KEY must be set to a private value (see infra/scripts/init-secrets.sh)"
        }
        require(jwtSecret.length >= MIN_SECRET_LENGTH && jwtSecret !in KNOWN_PLACEHOLDERS) {
            "TOKEN_SIGNING_KEY must be a private value of at least $MIN_SECRET_LENGTH characters"
        }
    }

    companion object {
        const val MIN_SECRET_LENGTH = 32
        private val KNOWN_PLACEHOLDERS = setOf(
            "change-me",
            "changeme",
            "change-me-32-bytes-minimum",
            // Defaults that used to be hardcoded in this repo; they are public.
            "k7m2-x9qd-4hrt-wc3f-p8ze-n6ay",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            enrollmentKey = env["ENROLLMENT_KEY"].orEmpty(),
            jwtSecret = env["TOKEN_SIGNING_KEY"].orEmpty(),
            enrollmentEnabled = env["ENROLLMENT_ENABLED"]?.lowercase() != "false"
        )
    }
}

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8081
    val config = ServerConfig.fromEnv()
    embeddedServer(Netty, port = port) {
        configureServer(config)
    }.start(wait = true)
}

private fun normalizeKey(key: String): ByteArray =
    key.replace("-", "").replace(" ", "").lowercase().toByteArray()

/** Stable per-database id, created by migration V5. Unlike a per-process id it survives restarts. */
fun loadServerId(): String = transaction {
    ServerMeta.selectAll().where { ServerMeta.key eq "server_id" }.single()[ServerMeta.value]
}

fun Application.configureServer(config: ServerConfig = ServerConfig.fromEnv(),
    initDatabase: Boolean = true,
    rateLimiter: com.gymtracker.server.common.RateLimiter = com.gymtracker.server.common.RateLimiter.shared
) {
    if (initDatabase) {
        DatabaseFactory.init(
            System.getenv("JDBC_URL") ?: System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/gymtracker",
            System.getenv("POSTGRES_USER") ?: "gymtracker",
            System.getenv("POSTGRES_PASSWORD").orEmpty()
        )
    }

    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
        }
        exception<IllegalArgumentException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
        }
        exception<Throwable> { call, cause ->
            // Log the detail, never send it: exception text can contain SQL and internals.
            call.application.log.error("Unhandled error on ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error"))
        }
    }
    installRateLimit(rateLimiter)

    val serverId = loadServerId()
    val authService = AuthService(config.jwtSecret)
    val syncService = SyncService()

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(config.jwtSecret)).build()
            )
            validate { credential ->
                val deviceId = credential.payload.getClaim("device_id").asString()
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (credential.payload.subject != null && deviceId != null && authService.isDeviceActive(deviceId)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }

    routing {
        get("/v1/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    version = "0.1.0",
                    minClientVersion = "0.1.0",
                    serverId = serverId
                )
            )
        }

        post("/v1/devices/enroll") {
            if (!config.enrollmentEnabled) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "enrollment_disabled"))
            }
            val request = try {
                call.receive<EnrollRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            }

            // Ignore dashes, spaces and case, and compare in constant time.
            if (!MessageDigest.isEqual(normalizeKey(request.enrollmentKey), normalizeKey(config.enrollmentKey))) {
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid enrollment key"))
            }

            val pair = authService.enrollDevice(request.deviceName.take(100), request.deviceKind.take(20))

            call.respond(
                EnrollResponse(
                    accessToken = pair.accessToken,
                    refreshToken = pair.refreshToken,
                    deviceId = pair.deviceId.toString()
                )
            )
        }
    }

    authRoutes(authService)
    syncRoutes(syncService)
}
