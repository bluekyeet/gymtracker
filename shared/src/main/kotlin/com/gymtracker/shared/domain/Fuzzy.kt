package com.gymtracker.shared.domain

private val WHITESPACE = Regex("\\s+")

/**
 * Score of [query] against [text], higher is a better match, null when it does not match at all.
 * Every whitespace-separated word of the query must match, so "bench squat" never finds "Bench Press".
 * A blank query matches everything with score 0, which leaves the caller's order untouched.
 */
fun fuzzyScore(query: String, text: String): Int? {
    val tokens = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return 0
    val haystack = text.lowercase()
    var total = 0
    for (token in tokens) {
        total += tokenScore(token, haystack) ?: return null
    }
    return total
}

private fun tokenScore(token: String, haystack: String): Int? {
    val index = haystack.indexOf(token)
    if (index == 0) return 100
    if (index > 0) {
        val atWordStart = !haystack[index - 1].isLetterOrDigit()
        return (if (atWordStart) 80 else 60) - minOf(index, 20)
    }
    var t = 0
    for (c in haystack) {
        if (c == token[t]) {
            t++
            if (t == token.length) return 30
        }
    }
    return null
}

fun <T> Iterable<T>.fuzzyFilter(query: String, selector: (T) -> String): List<T> =
    mapNotNull { item -> fuzzyScore(query, selector(item))?.let { item to it } }
        .sortedByDescending { it.second }
        .map { it.first }
