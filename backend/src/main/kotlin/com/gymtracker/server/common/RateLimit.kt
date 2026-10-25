package com.gymtracker.server.common

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object RateLimiter {
    private val buckets = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(key: String, limit: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val list = buckets.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it < now - windowMs }
            if (list.size >= limit) return false
            list.add(now)
            return true
        }
    }
}

fun Application.installRateLimit() {
    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.local.uri
        val ip = call.request.local.remoteHost
        val key = when {
            path.contains("/auth/login") -> "login:$ip"
            path.contains("/sync") -> "sync:$ip"
            else -> null
        }
        if (key != null) {
            val (limit, window) = if (path.contains("/auth/login")) 5 to 60_000L else 60 to 60_000L
            if (!RateLimiter.allow(key, limit, window)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
                finish()
            }
        }
    }
}
