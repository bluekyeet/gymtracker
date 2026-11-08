package com.gymtracker.data.sync

/**
 * Turns whatever the user typed (or a QR code carried) into the server's base URL, and decides
 * whether it is safe to talk to over plain http.
 *
 * Bearer tokens and the enrollment key travel in every request. The platform has to keep allowing
 * cleartext (a network security config cannot express "private address ranges"), so the rule lives
 * here instead: plain http is accepted only for hosts on the user's own network or an encrypted
 * overlay such as Tailscale. Anything on the public internet must be https.
 */
object ServerUrl {
    class InsecureServerUrl(message: String) : IllegalArgumentException(message)

    /** Returns `scheme://host[:port]` without a trailing slash, or throws [InsecureServerUrl] / [IllegalArgumentException]. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "Server URL is empty" }

        val explicitScheme = Regex("^(https?)://", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.lowercase()
        val withoutScheme = (if (explicitScheme != null) trimmed.substring(explicitScheme.length + 3) else trimmed).trimEnd('/')
        val host = hostOf(withoutScheme)
        require(host.isNotBlank()) { "Server URL has no host" }

        val scheme = when (explicitScheme) {
            "https" -> "https"
            "http" -> if (isPrivateHost(host)) "http" else throw InsecureServerUrl(INSECURE_MESSAGE)
            // Nothing typed: plain http is fine on the home network, anything else gets https.
            else -> if (isPrivateHost(host)) "http" else "https"
        }
        return "$scheme://$withoutScheme"
    }

    private const val INSECURE_MESSAGE =
        "Plain http:// is only allowed for servers on your own network (192.168.x.x, 10.x.x.x, " +
            "172.16-31.x.x, Tailscale 100.x.x.x, *.local, localhost). Use https:// for this server."

    internal fun hostOf(authorityAndPath: String): String {
        val authority = authorityAndPath.substringBefore('/').substringBefore('?').substringAfterLast('@')
        return if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']') // IPv6 literal
        } else {
            authority.substringBefore(':')
        }.lowercase()
    }

    internal fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.contains(':')) return isPrivateIpv6(host)
        ipv4(host)?.let { return isPrivateIpv4(it) }
        // Names that only resolve on a local network or inside a Tailscale tailnet (WireGuard-encrypted).
        if (!host.contains('.')) return true
        return listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net").any { host.endsWith(it) }
    }

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { o -> o.all { it in 0..255 } }
    }

    private fun isPrivateIpv4(o: List<Int>): Boolean = when {
        o[0] == 10 -> true
        o[0] == 127 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 100 && o[1] in 64..127 -> true // CGNAT range, used by Tailscale
        else -> false
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val h = host.lowercase()
        return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") ||
            h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
    }
}
