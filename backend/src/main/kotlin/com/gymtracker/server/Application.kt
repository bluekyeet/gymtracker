package com.gymtracker.server

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

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8081
    embeddedServer(Netty, port = port) {
        configureServer()
    }.start(wait = true)
}

fun Application.configureServer() {
    DatabaseFactory.init(
        System.getenv("JDBC_URL") ?: System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/gymtracker",
        System.getenv("POSTGRES_USER") ?: "gymtracker",
        System.getenv("POSTGRES_PASSWORD") ?: "change-me"
    )

    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.localizedMessage ?: "Unknown error")))
        }
    }

    val serverId = UUID.randomUUID().toString()
    val configuredEnrollmentKey = System.getenv("ENROLLMENT_KEY") ?: "k7m2-x9qd-4hrt-wc3f-p8ze-n6ay"
    val jwtSecret = System.getenv("TOKEN_SIGNING_KEY") ?: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    val authService = AuthService(jwtSecret)
    val syncService = SyncService()

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtSecret)).build()
            )
            validate { credential ->
                if (credential.payload.subject != null) {
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
            val request = try {
                call.receive<EnrollRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            }

            // Normalize enrollment key comparison: ignore dashes, spaces, and letter case
            val normalizedInput = request.enrollmentKey.replace("-", "").replace(" ", "").lowercase()
            val normalizedConfigured = configuredEnrollmentKey.replace("-", "").replace(" ", "").lowercase()

            if (normalizedInput != normalizedConfigured) {
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid enrollment key"))
            }

            val pair = authService.enrollDevice(request.deviceName, request.deviceKind)

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
