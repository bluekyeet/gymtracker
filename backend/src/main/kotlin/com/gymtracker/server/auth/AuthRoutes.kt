package com.gymtracker.server.auth

import com.gymtracker.server.database.Devices
import com.gymtracker.server.database.Users
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

@Serializable
data class LoginRequest(val email: String, val password: String, val deviceName: String = "device", val deviceKind: String = "phone")

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(val accessToken: String, val refreshToken: String, val deviceId: String)

@Serializable
data class DeviceResponse(val id: String, val name: String, val kind: String, val revoked: Boolean)

fun Application.authRoutes(authService: AuthService) {
    routing {
        post("/v1/auth/login") {
            val req = call.receive<LoginRequest>()
            val pair = authService.login(req.email, req.password, req.deviceName, req.deviceKind)
            if (pair == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_credentials"))
            } else {
                call.respond(TokenResponse(pair.accessToken, pair.refreshToken, pair.deviceId.toString()))
            }
        }
        post("/v1/auth/refresh") {
            val req = call.receive<RefreshRequest>()
            val pair = authService.refresh(req.refreshToken)
            if (pair == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_refresh"))
            } else {
                call.respond(TokenResponse(pair.accessToken, pair.refreshToken, pair.deviceId.toString()))
            }
        }
        authenticate("auth-jwt") {
            get("/v1/devices") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.subject)
                val devices = authService.listDevices(userId).map { DeviceResponse(it.id.toString(), it.name, it.kind, it.revokedAt != null) }
                call.respond(devices)
            }
            post("/v1/devices/{id}/revoke") {
                val id = UUID.fromString(call.parameters["id"]!!)
                authService.revokeDevice(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
