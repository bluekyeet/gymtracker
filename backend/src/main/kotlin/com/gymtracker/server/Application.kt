package com.gymtracker.server

import com.auth0.jwt.algorithms.Algorithm
import com.gymtracker.server.auth.AuthService
import com.gymtracker.server.sync.SyncService
import com.gymtracker.server.common.installRateLimit
import com.gymtracker.server.sync.syncRoutes
import com.gymtracker.server.auth.authRoutes
import com.gymtracker.server.database.DatabaseFactory
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.json

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "create-user") {
        val email = args.getOrElse(1) { error("email required") }
        val password = args.getOrElse(2) { error("password required") }
        createAccount(email, password)
        return
    }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) { module() }.start(wait = true)
}

fun createAccount(email: String, password: String) {
    val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/gymtracker"
    val dbUser = System.getenv("POSTGRES_USER") ?: "gymtracker"
    val dbPass = System.getenv("POSTGRES_PASSWORD") ?: "change-me"
    DatabaseFactory.init(jdbcUrl, dbUser, dbPass)
    val auth = AuthService(System.getenv("JWT_SECRET") ?: "dev-secret")
    auth.createUser(email, password)
    println("Created user $email")
}

fun Application.module() {
    val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/gymtracker"
    val dbUser = System.getenv("POSTGRES_USER") ?: "gymtracker"
    val dbPass = System.getenv("POSTGRES_PASSWORD") ?: "change-me"
    val jwtSecret = System.getenv("JWT_SECRET") ?: "dev-secret"
    DatabaseFactory.init(jdbcUrl, dbUser, dbPass)
    val authService = AuthService(jwtSecret)

    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
        json()
    }
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(com.auth0.jwt.JWT.require(Algorithm.HMAC256(jwtSecret)).build())
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
        }
    }
    installRateLimit()
    val syncService = SyncService()
    authRoutes(authService)
    syncRoutes(syncService)
}
