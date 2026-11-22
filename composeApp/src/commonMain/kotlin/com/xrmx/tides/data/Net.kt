package com.xrmx.tides.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

class SourceException(message: String) : Exception(message)

internal object Net {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val client by lazy {
        HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 45_000
                connectTimeoutMillis = 15_000
            }
        }
    }

    suspend fun getText(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val (response, body) = try {
            val response = client.get(url) {
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            response to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SourceException(friendlyNetworkError(e))
        }
        if (!response.status.isSuccess()) {
            val reason = when (response.status.value) {
                401, 403 -> "the API key was rejected. Check it in Settings"
                404 -> "not found"
                429 -> "too many requests, try again later"
                in 500..599 -> "the server is having problems (HTTP ${response.status.value}), try again later"
                else -> "HTTP ${response.status.value}"
            }
            throw SourceException("Request failed: $reason")
        }
        return body
    }

    suspend fun getJson(url: String, extraHeaders: Map<String, String> = emptyMap()): JsonElement {
        val text = getText(url, extraHeaders)
        return try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw SourceException("The server sent an unexpected response. Try again later.")
        }
    }
}

/** Runs a response parser, reporting any shape mismatch as a readable error. */
internal inline fun <T> parsing(block: () -> T): T = try {
    block()
} catch (e: SourceException) {
    throw e
} catch (e: Exception) {
    throw SourceException("The server sent an unexpected response. Try again later.")
}

/**
 * Turns low-level network exceptions into something a person can act on. Matches on class names
 * because the concrete types differ per platform (java.net on Android, NSURLError on iOS).
 */
internal fun friendlyNetworkError(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.take(5).toList()
    val text = chain.joinToString(" ") { "${it::class.simpleName} ${it.message.orEmpty()}" }
    return when {
        text.contains("Timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true) ->
            "The server took too long to respond. Check your connection and try again."
        listOf("UnknownHost", "UnresolvedAddress", "NoRouteToHost", "offline", "not connected", "Network is unreachable", "-1009")
            .any { text.contains(it, ignoreCase = true) } ->
            "No internet connection. Connect to the internet and try again."
        text.contains("Connect", ignoreCase = true) || text.contains("Socket", ignoreCase = true) ->
            "Couldn't reach the server. Check your connection and try again."
        text.contains("SSL", ignoreCase = true) || text.contains("certificate", ignoreCase = true) ->
            "A secure connection couldn't be made. Check the device date and time, then try again."
        else -> "Couldn't connect. Check your connection and try again."
    }
}

/** Lenient JSON helpers: some APIs return a number as a string, or an array where a scalar is expected. */
internal fun JsonElement?.asDouble(): Double? = when (this) {
    is JsonPrimitive -> doubleOrNull ?: contentOrNull?.toDoubleOrNull()
    is JsonArray -> firstOrNull()?.asDouble()
    else -> null
}

internal fun JsonElement?.asString(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> firstOrNull()?.asString()
    else -> null
}

/** Parses an ISO date-time with no zone, or with a Z, as UTC. */
internal fun parseUtcEpochSeconds(raw: String): Long {
    var s = raw.trim().removeSuffix("Z").substringBefore('+').substringBefore('.')
    if (s.length == 16) s += ":00"
    return LocalDateTime.parse(s).toInstant(TimeZone.UTC).epochSeconds
}

internal fun String.toTitleCaseIfShouting(): String =
    if (this != uppercase()) this
    else lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
