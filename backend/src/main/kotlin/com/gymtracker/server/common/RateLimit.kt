package com.gymtracker.server.common

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.util.concurrent.ConcurrentHashMap

class RateLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    private val buckets = ConcurrentHashMap<String, MutableList<Long>>()
    private var lastSweep = clock()

    fun allow(key: String, limit: Int, windowMs: Long): Boolean {
        val now = clock()
        sweep(now, windowMs)
        val list = buckets.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it < now - windowMs }
            if (list.size >= limit) return false
            list.add(now)
            return true
        }
    }

    /** Drops idle keys so a stream of distinct clients cannot grow the map without bound. */
    private fun sweep(now: Long, windowMs: Long) {
        if (now - lastSweep < windowMs) return
        lastSweep = now
        buckets.entries.removeIf { (_, list) -> synchronized(list) { list.none { it >= now - windowMs } } }
    }

    companion object {
        val shared = RateLimiter()
    }
}

private data class Rule(val name: String, val limit: Int)

private fun ruleFor(path: String): Rule? = when {
    path.startsWith("/v1/auth/login") -> Rule("login", 5)
    // Enrollment key guessing is the one unauthenticated way in, so it is limited hardest.
    path.startsWith("/v1/devices/enroll") -> Rule("enroll", 5)
    path.startsWith("/v1/auth/refresh") -> Rule("refresh", 30)
    path.startsWith("/v1/sync") -> Rule("sync", 120)
    else -> null
}

fun Application.installRateLimit(limiter: RateLimiter = RateLimiter.shared) {
    intercept(ApplicationCallPipeline.Plugins) {
        val rule = ruleFor(call.request.local.uri) ?: return@intercept
        val client = call.request.local.remoteAddress
        if (!limiter.allow("${rule.name}:$client", rule.limit, 60_000L)) {
            call.response.header(HttpHeaders.RetryAfter, "60")
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
            finish()
        }
    }
}
