package com.gymtracker.server.sync

import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PullQuery(val cursor: String? = null, val limit: Int? = null)

fun Application.syncRoutes(syncService: SyncService) {
    routing {
        authenticate("auth-jwt") {
            post("/v1/sync/push") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val req = call.receive<com.gymtracker.shared.sync.SyncPushRequest>()
                call.respond(syncService.push(userId, req))
            }
            get("/v1/sync/pull") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val cursor = call.request.queryParameters["cursor"] ?: "0"
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                call.respond(syncService.pull(userId, cursor, limit))
            }
        }
    }
}
