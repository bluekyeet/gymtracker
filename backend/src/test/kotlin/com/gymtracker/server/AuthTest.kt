package com.gymtracker.server

import com.gymtracker.server.auth.AuthService
import com.gymtracker.server.database.DatabaseFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthTest {
    @Test
    fun loginAndRefresh() {
        val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/gymtracker"
        val user = System.getenv("POSTGRES_USER") ?: "gymtracker"
        val pass = System.getenv("POSTGRES_PASSWORD") ?: "change-me"
        DatabaseFactory.init(jdbcUrl, user, pass)
        val auth = AuthService(System.getenv("JWT_SECRET") ?: "dev-secret")
        val email = "test-${java.util.UUID.randomUUID()}@example.com"
        auth.createUser(email, "pw")
        val pair = auth.login(email, "pw", "phone", "phone")
        assertNotNull(pair)
        val refreshed = auth.refresh(pair!!.refreshToken)
        assertNotNull(refreshed)
        val reuse = auth.refresh(pair.refreshToken)
        assertNull(reuse)
    }
}
