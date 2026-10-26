package com.xrmx.tides.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
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
        val response = client.get(url) {
            extraHeaders.forEach { (k, v) -> header(k, v) }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val reason = when (response.status.value) {
                401, 403 -> "the API key was rejected"
                404 -> "not found"
                429 -> "too many requests, try again later"
                else -> "HTTP ${response.status.value}"
            }
            throw SourceException("Request failed: $reason")
        }
        return body
    }

    suspend fun getJson(url: String, extraHeaders: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(getText(url, extraHeaders))
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
