package com.gymtracker.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.OutboxEntity
import com.gymtracker.data.db.put
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.sync.ChangeDto
import com.gymtracker.shared.sync.OpResult
import com.gymtracker.shared.sync.SyncPullResponse
import com.gymtracker.shared.sync.SyncPushRequest
import com.gymtracker.shared.sync.SyncPushResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SyncTests {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager
    private lateinit var server: ServerClient
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val base = "http://fake.local"

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sync = SyncManager(context, db)
        server = ServerClient(context, db).also { it.serverUrl = base }
    }

    @After
    fun teardown() = db.close()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(json) } }

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)

    private fun jwt(expiresInSeconds: Long, subject: UUID = UUID.randomUUID()): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256"}""".toByteArray())
        val exp = System.currentTimeMillis() / 1000 + expiresInSeconds
        val payload = enc.encodeToString("""{"sub":"$subject","exp":$exp}""".toByteArray())
        return "$header.$payload.sig"
    }

    private fun health(id: String = "server-A") = """{"status":"ok","version":"1","minClientVersion":"1","serverId":"$id"}"""

    private fun tokens(access: String = jwt(900), refresh: String = "refresh-2") =
        """{"accessToken":"$access","refreshToken":"$refresh","deviceId":"${UUID.randomUUID()}"}"""

    private suspend fun queue(entity: String, id: UUID = UUID.randomUUID()): UUID {
        val op = OutboxEntity(UUID.randomUUID(), entity, id, "upsert", 0, "{}")
        db.outboxDao().put(op)
        return op.opId
    }

    private fun emptyPull(cursor: String = "0") = json.encodeToString(SyncPullResponse(emptyList(), cursor, false))

    private fun pairedDevice(access: String = jwt(900)) {
        server.accessToken = access
        server.refreshToken = "refresh-1"
        server.deviceId = UUID.randomUUID().toString()
    }

    // --- push --------------------------------------------------------------------------------------------

    @Test
    fun pushDeletesFinalVerdictsButKeepsRetries() = runBlocking {
        val applied = queue("exercise")
        val rejected = queue("exercise")
        val retry = queue("exercise")
        val c = client { req ->
            val sent = json.decodeFromString<SyncPushRequest>(String(req.body.toByteArray()))
            val byId = sent.operations.map { it.opId }
            assertEquals(3, byId.size)
            ok(
                json.encodeToString(
                    SyncPushResponse(
                        listOf(
                            OpResult(applied.toString(), "applied", 1),
                            OpResult(rejected.toString(), "rejected", reason = "invalid_data"),
                            OpResult(retry.toString(), "retry", reason = "server_error")
                        )
                    )
                )
            )
        }
        sync.pushPending(base, "tok", UUID.randomUUID(), 1, c)
        assertEquals(listOf(retry), db.outboxDao().getAll().map { it.opId })
    }

    @Test
    fun aFailedPushLeavesTheOutboxUntouched() = runBlocking {
        queue("exercise")
        queue("split")
        val c = client { respond("boom", HttpStatusCode.InternalServerError) }
        runCatching { sync.pushPending(base, "tok", UUID.randomUUID(), 1, c) }
        assertEquals(2, db.outboxDao().getAll().size)
    }

    @Test
    fun parentsArePushedBeforeChildren() = runBlocking {
        queue("set")
        queue("workout_exercise")
        queue("workout_session")
        queue("split_day")
        queue("split")
        queue("exercise")
        var order = emptyList<String>()
        val c = client { req ->
            order = json.decodeFromString<SyncPushRequest>(String(req.body.toByteArray())).operations.map { it.entity }
            ok(json.encodeToString(SyncPushResponse(emptyList())))
        }
        sync.pushPending(base, "tok", UUID.randomUUID(), 1, c)
        assertEquals(listOf("exercise", "split", "split_day", "workout_session", "workout_exercise", "set"), order)
    }

    @Test
    fun anUnauthorizedPushRaisesAuthExceptionAndKeepsTheOutbox() = runBlocking {
        queue("exercise")
        val c = client { respond("", HttpStatusCode.Unauthorized) }
        val failure = runCatching { sync.pushPending(base, "tok", UUID.randomUUID(), 1, c) }.exceptionOrNull()
        assertTrue(failure is SyncAuthException)
        assertEquals(1, db.outboxDao().getAll().size)
    }

    @Test
    fun largeBacklogIsPushedInChunks() = runBlocking {
        repeat(450) { queue("exercise") }
        val sizes = mutableListOf<Int>()
        val c = client { req ->
            val sent = json.decodeFromString<SyncPushRequest>(String(req.body.toByteArray()))
            sizes += sent.operations.size
            ok(json.encodeToString(SyncPushResponse(sent.operations.map { OpResult(it.opId, "applied", 1) })))
        }
        sync.pushPending(base, "tok", UUID.randomUUID(), 1, c)
        assertEquals(listOf(200, 200, 50), sizes)
        assertTrue(db.outboxDao().getAll().isEmpty())
    }

    // --- pull and the cursor -----------------------------------------------------------------------------

    private fun exerciseChange(seq: Long, ex: Exercise) =
        ChangeDto(seq, "exercise", ex.id.toString(), 1, false, json.encodeToJsonElement(ex))

    @Test
    fun cursorAdvancesOnlyAfterAPageIsFullyApplied() = runBlocking {
        val first = Exercise(UUID.randomUUID(), name = "Bench Press")
        val brokenSet = ChangeDto(2, "set", UUID.randomUUID().toString(), 1, false, buildJsonObject { put("nonsense", "x") })
        val pages = mapOf(
            "0" to SyncPullResponse(listOf(exerciseChange(1, first)), "1", true),
            "1" to SyncPullResponse(listOf(brokenSet), "2", false)
        )
        val c = client { req -> ok(json.encodeToString(pages.getValue(req.url.parameters["cursor"]!!))) }

        val failure = runCatching { sync.pull(base, "tok", c) }.exceptionOrNull()

        assertNotNull("the malformed page must fail the pull", failure)
        assertEquals("1", sync.getCursor()) // page 1 stored, page 2 NOT
        assertEquals("Bench Press", db.exerciseDao().getById(first.id)?.name)
    }

    @Test
    fun aPageThatFailsHalfWayAppliesNothingAndKeepsTheCursor() = runBlocking {
        val good = Exercise(UUID.randomUUID(), name = "Squat")
        val bad = ChangeDto(2, "set", UUID.randomUUID().toString(), 1, false, JsonObject(emptyMap()))
        val c = client { ok(json.encodeToString(SyncPullResponse(listOf(exerciseChange(1, good), bad), "2", false))) }
        runCatching { sync.pull(base, "tok", c) }
        assertEquals("0", sync.getCursor())
        assertNull("a half-applied page must roll back", db.exerciseDao().getById(good.id))
    }

    @Test
    fun pullAppliesDeletesAndTheServersCopy() = runBlocking {
        val ex = Exercise(UUID.randomUUID(), name = "Old name")
        db.exerciseDao().upsert(ex.toEntity())
        val renamed = ex.copy(name = "New name", revision = 2)
        val c = client {
            ok(json.encodeToString(SyncPullResponse(listOf(exerciseChange(5, renamed)), "5", false)))
        }
        sync.pull(base, "tok", c)
        assertEquals("New name", db.exerciseDao().getById(ex.id)?.name)
        assertEquals("5", sync.getCursor())

        val deleteChange = ChangeDto(6, "exercise", ex.id.toString(), 3, true, null)
        val c2 = client { ok(json.encodeToString(SyncPullResponse(listOf(deleteChange), "6", false))) }
        sync.pull(base, "tok", c2)
        assertNotNull(db.exerciseDao().getById(ex.id)?.deletedAt)
    }

    @Test
    fun pullDoesNotOverwriteARowWithAPendingLocalEdit() = runBlocking {
        val edited = Exercise(UUID.randomUUID(), name = "Typed offline")
        val untouched = Exercise(UUID.randomUUID(), name = "Before")
        db.exerciseDao().upsert(edited.toEntity())
        db.exerciseDao().upsert(untouched.toEntity())
        queue("exercise", edited.id)

        val c = client {
            ok(
                json.encodeToString(
                    SyncPullResponse(
                        listOf(
                            exerciseChange(1, edited.copy(name = "Stale server copy")),
                            exerciseChange(2, untouched.copy(name = "After"))
                        ),
                        "2", false
                    )
                )
            )
        }
        sync.pull(base, "tok", c)

        assertEquals("Typed offline", db.exerciseDao().getById(edited.id)?.name)
        assertEquals("After", db.exerciseDao().getById(untouched.id)?.name)
    }

    // --- server identity (#8) ------------------------------------------------------------------------------

    @Test
    fun aDifferentServerIdResetsTheCursorAndRequeuesLocalData() {
        sync.setCursor("99")
        assertEquals(false, sync.reconcileServerIdentity("A")) // first contact: nothing to reset
        assertEquals("99", sync.getCursor())
        assertEquals(false, sync.reconcileServerIdentity("A"))
        assertEquals("99", sync.getCursor())

        assertEquals(true, sync.reconcileServerIdentity("B")) // restored / recreated database
        assertEquals("0", sync.getCursor())
        assertEquals(true, sync.reconcileServerIdentity("A") && sync.getCursor() == "0")
    }

    @Test
    fun restoredServerIsRepopulatedAndPulledFromTheStart() = runBlocking {
        pairedDevice()
        val ex = Exercise(UUID.randomUUID(), name = "Deadlift")
        db.exerciseDao().upsert(ex.toEntity())
        val serverId = arrayOf("A")
        val cursors = mutableListOf<String>()
        val pushedEntities = mutableListOf<String>()
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v1/health" -> ok(health(serverId[0]))
                "/v1/sync/push" -> {
                    val sent = json.decodeFromString<SyncPushRequest>(String(req.body.toByteArray()))
                    sent.operations.forEach { pushedEntities += "${serverId[0]}:${it.entity}" }
                    ok(json.encodeToString(SyncPushResponse(sent.operations.map { OpResult(it.opId, "applied", 1) })))
                }
                "/v1/sync/pull" -> {
                    cursors += req.url.parameters["cursor"]!!
                    ok(json.encodeToString(SyncPullResponse(emptyList(), "40", false)))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals(SyncResult.Success, sync.performFullSync(server, c))
        assertEquals("40", sync.getCursor())

        serverId[0] = "B" // the server's database was swapped for an older backup
        assertEquals(SyncResult.Success, sync.performFullSync(server, c))

        assertEquals(listOf("0", "0"), cursors) // second sync started over instead of resuming at 40
        assertTrue("local data must be re-pushed to the new server", "B:exercise" in pushedEntities)
    }

    // --- one sync at a time (#7) ---------------------------------------------------------------------------

    @Test
    fun concurrentSyncsRunOneAtATime() = runBlocking {
        pairedDevice()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val c = client { req ->
            if (req.url.encodedPath == "/v1/health") {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                delay(60)
                inFlight.decrementAndGet()
                ok(health())
            } else ok(emptyPull())
        }
        val results = (1..4).map { async { sync.performFullSync(server, c) } }.awaitAll()
        assertTrue(results.all { it == SyncResult.Success })
        assertEquals("syncs must not overlap", 1, maxInFlight.get())
    }

    // --- auth during sync (#7) -------------------------------------------------------------------------------

    @Test
    fun anExpiredAccessTokenIsRefreshedBeforeAnyRequestNeedsIt() = runBlocking {
        pairedDevice(access = jwt(-60))
        val fresh = jwt(900)
        val unauthorized = AtomicInteger()
        val refreshes = AtomicInteger()
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> { refreshes.incrementAndGet(); ok(tokens(access = fresh)) }
                "/v1/health" -> ok(health())
                else -> {
                    if (req.headers[HttpHeaders.Authorization] != "Bearer $fresh") unauthorized.incrementAndGet()
                    ok(if (req.url.encodedPath.endsWith("pull")) emptyPull() else json.encodeToString(SyncPushResponse(emptyList())))
                }
            }
        }
        queue("exercise")
        assertEquals(SyncResult.Success, sync.performFullSync(server, c))
        assertEquals(1, refreshes.get())
        assertEquals("no request should have been sent with the stale token", 0, unauthorized.get())
        assertEquals("refresh-2", server.refreshToken)
    }

    @Test
    fun aRevokedTokenIsRenewedOnceAndTheSyncRetried() = runBlocking {
        val stale = jwt(900)
        val fresh = jwt(900)
        pairedDevice(access = stale)
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> ok(tokens(access = fresh))
                "/v1/health" -> ok(health())
                else -> if (req.headers[HttpHeaders.Authorization] == "Bearer $stale") respond("", HttpStatusCode.Unauthorized)
                else ok(if (req.url.encodedPath.endsWith("pull")) emptyPull() else json.encodeToString(SyncPushResponse(emptyList())))
            }
        }
        queue("exercise")
        assertEquals(SyncResult.Success, sync.performFullSync(server, c))
        assertEquals(fresh, server.accessToken)
    }

    @Test
    fun anUnreachableServerDuringRefreshIsNotTreatedAsUnpaired() = runBlocking {
        pairedDevice(access = jwt(-60))
        server.enrollmentKey = "my-key"
        val enrolls = AtomicInteger()
        val c = client { req ->
            if (req.url.encodedPath == "/v1/devices/enroll") enrolls.incrementAndGet()
            throw java.io.IOException("connection reset")
        }
        val result = sync.performFullSync(server, c)
        assertTrue(result is SyncResult.NetworkError)
        assertEquals("a flaky network must not register a new device", 0, enrolls.get())
        assertEquals("refresh-1", server.refreshToken) // nothing was thrown away
    }

    @Test
    fun serverErrorsDuringRefreshAreNetworkErrorsToo() = runBlocking {
        pairedDevice(access = jwt(-60))
        val c = client { respond("", HttpStatusCode.ServiceUnavailable) }
        assertTrue(sync.performFullSync(server, c) is SyncResult.NetworkError)
    }

    @Test
    fun aRefusedRefreshWithoutAnEnrollmentKeyIsAnAuthError() = runBlocking {
        pairedDevice(access = jwt(-60))
        val c = client { respond("", HttpStatusCode.Unauthorized) }
        assertTrue(sync.performFullSync(server, c) is SyncResult.AuthError)
    }

    @Test
    fun aRefusedRefreshReEnrollsWithTheStoredKeyAsTheRightKindOfDevice() = runBlocking {
        pairedDevice(access = jwt(-60))
        server.enrollmentKey = "my-key"
        server.deviceKind = "watch"
        var enrollBody = ""
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> respond("", HttpStatusCode.Unauthorized)
                "/v1/devices/enroll" -> { enrollBody = String(req.body.toByteArray()); ok(tokens()) }
                "/v1/health" -> ok(health())
                else -> ok(if (req.url.encodedPath.endsWith("pull")) emptyPull() else json.encodeToString(SyncPushResponse(emptyList())))
            }
        }
        assertEquals(SyncResult.Success, sync.performFullSync(server, c))
        assertTrue(enrollBody, enrollBody.contains("\"deviceKind\":\"watch\""))
    }

    @Test
    fun enrollingAsAWatchSendsTheWatchKind() = runBlocking {
        var body = ""
        val c = client { req -> body = String(req.body.toByteArray()); ok(tokens()) }
        server.enroll(base, "key", "Pixel Watch", c, "watch")
        assertTrue(body, body.contains("\"deviceKind\":\"watch\""))
        assertEquals("watch", server.deviceKind)
    }

    @Test
    fun enrollmentFailuresSayWhatWentWrong() = runBlocking {
        fun messageFor(status: HttpStatusCode): String? = runBlocking {
            runCatching { server.enroll(base, "k", "n", client { respond("", status) }) }.exceptionOrNull()?.message
        }
        assertEquals("Invalid enrollment key", messageFor(HttpStatusCode.Unauthorized))
        assertTrue(messageFor(HttpStatusCode.Forbidden)!!.contains("turned off"))
        assertTrue(messageFor(HttpStatusCode.TooManyRequests)!!.contains("Wait"))
    }

    @Test
    fun enrollingAdoptsTheServersUserIdForNewRows() = runBlocking {
        val user = UUID.randomUUID()
        val c = client { ok(tokens(access = jwt(900, user))) }
        server.enroll(base, "key", "Pixel", c)
        assertEquals(user, com.gymtracker.data.OwnerId.current)
    }

    @Test
    fun syncRefusesPlainHttpToAPublicServerBeforeSendingAnything() = runBlocking {
        server.serverUrl = "http://gym.example.com"
        pairedDevice()
        val requests = AtomicInteger()
        val c = client { requests.incrementAndGet(); ok("{}") }
        val result = sync.performFullSync(server, c)
        assertTrue(result is SyncResult.OtherError)
        assertTrue((result as SyncResult.OtherError).message.contains("https://"))
        assertEquals("no token may be sent over plain http", 0, requests.get())
    }

    @Test
    fun enrollingOverPlainHttpToAPublicServerIsRefused() = runBlocking {
        val requests = AtomicInteger()
        val c = client { requests.incrementAndGet(); ok(tokens()) }
        val failure = runCatching { server.enroll("http://gym.example.com", "key", "Pixel", c) }.exceptionOrNull()
        assertTrue(failure is ServerUrl.InsecureServerUrl)
        assertEquals(0, requests.get())
    }

    @Test
    fun theLastSyncTimeIncludesTheDate() = runBlocking {
        pairedDevice()
        val c = client { req -> if (req.url.encodedPath == "/v1/health") ok(health()) else ok(emptyPull()) }
        assertEquals(SyncResult.Success, sync.performFullSync(server, c))
        // e.g. "8 Nov 14:05": a time-only "14:05" is indistinguishable from a sync made days ago.
        assertTrue(server.lastSyncTime!!, Regex("""\d{1,2} \S+ \d{2}:\d{2}""").containsMatchIn(server.lastSyncTime!!))
    }
}
