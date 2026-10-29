package com.scrobblecue.app.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * JSON settings for the backend and the public APIs: unknown fields are ignored (Discogs and
 * MusicBrainz send far more than we use) and absent values are left out when sending.
 */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** The backend's error body (RFC 9457 problem details). */
@Serializable
data class Problem(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
)

/** An error answer from the backend. [code] is the stable error slug, e.g. `lastfm-session-revoked`. */
class ApiException(val status: Int, val problem: Problem?) :
    Exception(problem?.detail ?: problem?.title ?: "Server error ($status)") {

    val code: String?
        get() = problem?.type?.substringAfter("/errors/", "")?.ifEmpty { null }

    /** The sign-in is no longer valid: the user has to connect again. */
    val needsReconnect: Boolean
        get() = code in setOf("credentials-missing", "credentials-invalid", "lastfm-session-revoked", "discogs-session-revoked")
}

/** The body of a successful answer, or an [ApiException] with the backend's problem details. */
fun <T> Response<T>.bodyOrThrow(): T {
    if (!isSuccessful) throw toApiException()
    // Retrofit returns Unit itself for empty answers declared as Response<Unit>
    return body() ?: throw ApiException(code(), Problem(detail = "Empty answer from the server"))
}

fun Response<*>.toApiException(): ApiException {
    val problem = errorBody()?.string()?.let { text ->
        runCatching { ApiJson.decodeFromString<Problem>(text) }.getOrNull()
    }
    return ApiException(code(), problem)
}
