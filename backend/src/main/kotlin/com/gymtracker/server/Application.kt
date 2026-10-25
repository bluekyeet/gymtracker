package com.gymtracker.server

import io.ktor.server.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
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
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.localizedMessage ?: "Unknown error")))
        }
    }

    val serverId = UUID.randomUUID().toString()
    val configuredEnrollmentKey = System.getenv("ENROLLMENT_KEY") ?: "k7m2-x9qd-4hrt-wc3f-p8ze-n6ay"

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
            // Placeholder auth/enrollment matching specification
            val request = try {
                call.receive<EnrollRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            }

            if (request.enrollmentKey != configuredEnrollmentKey) {
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid enrollment key"))
            }

            call.respond(
                EnrollResponse(
                    accessToken = "mock-access-token",
                    refreshToken = "mock-refresh-token",
                    deviceId = UUID.randomUUID().toString()
                )
            )
        }
    }
}
