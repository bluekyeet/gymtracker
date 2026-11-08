package com.gymtracker.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlTests {
    private fun n(raw: String) = ServerUrl.normalize(raw)

    @Test
    fun plainHttpIsAllowedOnYourOwnNetwork() {
        listOf(
            "http://192.168.1.20:8081", "http://10.0.2.2:8081", "http://10.1.2.3", "http://172.16.0.5", "http://172.31.255.1",
            "http://100.101.102.103:8081", "http://127.0.0.1:8081", "http://localhost:8081", "http://gym.local:8081",
            "http://nas:8081", "http://box.tail1234.ts.net:8081", "http://[::1]:8081", "http://[fd12:3456::1]:8081"
        ).forEach { assertEquals(it, n(it)) }
    }

    @Test
    fun plainHttpToThePublicInternetIsRefused() {
        listOf(
            "http://gym.example.com", "http://8.8.8.8:8081", "http://172.32.0.1", "http://172.15.0.1",
            "http://100.63.0.1", "http://100.128.0.1", "http://192.169.1.1", "http://[2001:db8::1]:8081", "HTTP://gym.example.com"
        ).forEach { url ->
            assertThrows(url, ServerUrl.InsecureServerUrl::class.java) { n(url) }
        }
    }

    @Test
    fun httpsIsAlwaysAllowed() {
        assertEquals("https://gym.example.com", n("https://gym.example.com"))
        assertEquals("https://api.gym.example.com:8443", n("https://api.gym.example.com:8443/"))
        assertEquals("https://192.168.1.20", n("https://192.168.1.20"))
    }

    @Test
    fun aBareHostGetsTheSafestSchemeForWhereItLives() {
        assertEquals("http://192.168.1.20:8081", n("192.168.1.20:8081"))
        assertEquals("http://nas.local", n("nas.local"))
        assertEquals("https://gym.example.com", n("gym.example.com"))
        assertEquals("https://8.8.8.8:8081", n("8.8.8.8:8081"))
    }

    @Test
    fun trailingSlashAndWhitespaceAreTrimmed() {
        assertEquals("http://192.168.1.20:8081", n("  http://192.168.1.20:8081/  "))
    }

    @Test
    fun aTrickyHostIsJudgedByItsRealHost() {
        // The userinfo part and a private-looking prefix in a public name must not fool the check.
        assertThrows(ServerUrl.InsecureServerUrl::class.java) { n("http://192.168.1.1@evil.example.com") }
        assertThrows(ServerUrl.InsecureServerUrl::class.java) { n("http://192.168.1.1.evil.example.com") }
        assertThrows(ServerUrl.InsecureServerUrl::class.java) { n("http://localhost.evil.example.com") }
    }

    @Test
    fun blankInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { n("   ") }
        assertThrows(IllegalArgumentException::class.java) { n("http://") }
    }
}
