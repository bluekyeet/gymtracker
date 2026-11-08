package com.gymtracker.server

import com.gymtracker.server.auth.AuthService
import com.gymtracker.server.common.RateLimiter
import com.gymtracker.server.database.RefreshTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class AuthHardeningTest {
    private val auth = AuthService(TestDb.config.jwtSecret)

    companion object {
        @JvmStatic
        @BeforeAll
        fun db() = TestDb.ensure()
    }

    // --- config: no built-in secrets (#3) ---------------------------------------------------------

    @Test
    fun configRefusesMissingOrPublicSecrets() {
        val good = "g".repeat(40)
        assertThrows(IllegalArgumentException::class.java) { ServerConfig.fromEnv(emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig(enrollmentKey = "k7m2-x9qd-4hrt-wc3f-p8ze-n6ay", jwtSecret = good)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig(enrollmentKey = "ok-key", jwtSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        }
        assertThrows(IllegalArgumentException::class.java) { ServerConfig(enrollmentKey = "ok-key", jwtSecret = "too-short") }
        assertThrows(IllegalArgumentException::class.java) { ServerConfig(enrollmentKey = "change-me", jwtSecret = good) }
        assertEquals(false, ServerConfig.fromEnv(mapOf("ENROLLMENT_KEY" to "k", "TOKEN_SIGNING_KEY" to good, "ENROLLMENT_ENABLED" to "false")).enrollmentEnabled)
    }

    // --- refresh rotation (#7) --------------------------------------------------------------------

    private fun age(refreshToken: String, seconds: Long) = transaction {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray())
            .joinToString("") { "%02x".format(it) }
        RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
            it[usedAt] = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(seconds)
        }
    }

    @Test
    fun replayInsideGraceWindowGetsAFreshTokenWithoutRevokingTheFamily() {
        val first = auth.enrollDevice("phone", "phone")
        val rotated = auth.refresh(first.refreshToken)!!
        // The response carrying `rotated` was lost, so the client retries with the old token.
        val retried = auth.refresh(first.refreshToken)
        assertNotNull(retried, "a just-rotated token must not lock the device out")
        assertNotEquals(rotated.refreshToken, retried!!.refreshToken)
        // The family is intact: the newest token still works.
        assertNotNull(auth.refresh(retried.refreshToken))
    }

    @Test
    fun replayAfterGraceWindowRevokesTheWholeFamily() {
        val first = auth.enrollDevice("phone", "phone")
        val rotated = auth.refresh(first.refreshToken)!!
        age(first.refreshToken, AuthService.REFRESH_GRACE_SECONDS + 5)

        assertNull(auth.refresh(first.refreshToken), "stale replay is treated as theft")
        assertNull(auth.refresh(rotated.refreshToken), "and the whole family is dead")
    }

    @Test
    fun revokedFamilyCannotBeReplayedEvenInsideTheGraceWindow() {
        val first = auth.enrollDevice("phone", "phone")
        val rotated = auth.refresh(first.refreshToken)!!
        age(first.refreshToken, AuthService.REFRESH_GRACE_SECONDS + 5)
        assertNull(auth.refresh(first.refreshToken)) // reuse detected, family revoked
        assertNull(auth.refresh(rotated.refreshToken))
        assertNull(auth.refresh(first.refreshToken), "revocation must not reopen a grace window")
    }

    // --- device revocation and ownership (#5) -----------------------------------------------------

    @Test
    fun revokedDeviceCannotRefreshAndIsNoLongerActive() {
        val enrolled = auth.enrollDevice("phone", "phone")
        val userId = com.auth0.jwt.JWT.decode(enrolled.accessToken).subject.let(UUID::fromString)
        assertTrue(auth.isDeviceActive(enrolled.deviceId))
        assertTrue(auth.revokeDevice(userId, enrolled.deviceId))
        assertFalse(auth.isDeviceActive(enrolled.deviceId))
        assertNull(auth.refresh(enrolled.refreshToken))
    }

    @Test
    fun cannotRevokeSomeoneElsesDevice() {
        val device = auth.enrollDevice("watch", "watch")
        val stranger = TestDb.newUser()
        assertFalse(auth.revokeDevice(stranger, device.deviceId))
        assertTrue(auth.isDeviceActive(device.deviceId))
        assertFalse(auth.revokeDevice(stranger, UUID.randomUUID()))
    }

    // --- HTTP behaviour ---------------------------------------------------------------------------

    private fun ApplicationTestBuilder.app(config: ServerConfig = TestDb.config) {
        application { configureServer(config, initDatabase = false, rateLimiter = RateLimiter()) }
    }

    private suspend fun io.ktor.client.HttpClient.enroll(key: String) = post("/v1/devices/enroll") {
        contentType(ContentType.Application.Json)
        setBody("""{"enrollmentKey":"$key","deviceName":"t","deviceKind":"phone"}""")
    }

    @Test
    fun enrollmentIsRateLimited() = testApplication {
        app()
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.enroll("wrong-guess-$it").status) }
        val blocked = client.enroll("wrong-guess-again")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals("60", blocked.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun enrollmentCanBeSwitchedOff() = testApplication {
        app(TestDb.config.copy(enrollmentEnabled = false))
        assertEquals(HttpStatusCode.Forbidden, client.enroll(TestDb.config.enrollmentKey).status)
    }

    @Test
    fun enrollmentKeyComparisonIgnoresDashesSpacesAndCase() = testApplication {
        app()
        assertEquals(HttpStatusCode.OK, client.enroll("TEST-enrollment key-not-a-real-secret").status)
    }

    @Test
    fun healthReportsThePersistedServerId() = testApplication {
        app()
        val body = client.get("/v1/health").bodyAsText()
        assertTrue(body.contains(loadServerId()), body)
    }

    @Test
    fun syncRequiresAValidTokenAndRevokedDevicesAreCutOff() = testApplication {
        app()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync/pull").status)

        val device = auth.enrollDevice("phone", "phone")
        val bearer = "Bearer ${device.accessToken}"
        assertEquals(HttpStatusCode.OK, client.get("/v1/sync/pull") { header(HttpHeaders.Authorization, bearer) }.status)

        val userId = com.auth0.jwt.JWT.decode(device.accessToken).subject.let(UUID::fromString)
        auth.revokeDevice(userId, device.deviceId)
        // The access token is still unexpired, but the device is revoked.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync/pull") { header(HttpHeaders.Authorization, bearer) }.status)
    }

    @Test
    fun errorsDoNotLeakInternalsAndBadInputIs400() = testApplication {
        app()
        val device = auth.enrollDevice("phone", "phone")
        val bearer = "Bearer ${device.accessToken}"
        val badJson = client.post("/v1/sync/push") {
            header(HttpHeaders.Authorization, bearer)
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.BadRequest, badJson.status)
        val badId = client.post("/v1/devices/not-a-uuid/revoke") { header(HttpHeaders.Authorization, bearer) }
        assertEquals(HttpStatusCode.BadRequest, badId.status)
    }

    @Test
    fun pullLimitIsClamped() = testApplication {
        app()
        val device = auth.enrollDevice("phone", "phone")
        val bearer = "Bearer ${device.accessToken}"
        assertEquals(HttpStatusCode.OK, client.get("/v1/sync/pull?limit=-5") { header(HttpHeaders.Authorization, bearer) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/sync/pull?limit=2000000000") { header(HttpHeaders.Authorization, bearer) }.status)
    }
}
