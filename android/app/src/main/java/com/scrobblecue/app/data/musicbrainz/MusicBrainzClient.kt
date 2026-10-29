package com.scrobblecue.app.data.musicbrainz

import com.scrobblecue.app.data.api.ApiJson
import com.scrobblecue.app.data.api.External
import com.scrobblecue.app.data.db.LookupDao
import com.scrobblecue.app.data.db.LookupDurations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Fills in track lengths Discogs doesn't have, via MusicBrainz's link to the Discogs release.
 * Same as web/src/external/musicbrainz.ts.
 */
@Singleton
class MusicBrainzClient @Inject constructor(
    @External private val http: OkHttpClient,
    private val lookups: LookupDao,
) {
    private val throttle = Mutex()
    private var lastRequest = 0L

    /**
     * Lengths in seconds by Discogs position. The result (also an empty one) is cached, so each release
     * is looked up at most once.
     *
     * @param allPositions every track position of the Discogs release, in order
     */
    suspend fun lookupDurations(releaseId: Long, allPositions: List<String>): Map<String, Int> {
        lookups.get(releaseId)?.let { return ApiJson.decodeFromString(it.durationsJson) }

        val durations = try {
            val link = get<UrlLookup>(
                "url",
                "resource" to "https://www.discogs.com/release/$releaseId",
                "inc" to "release-rels",
            )
            val mbid = link?.relations?.firstNotNullOfOrNull { it.release?.id }
            val release = mbid?.let { get<Release>("release/$it", "inc" to "recordings") }
            release?.let { matchDurations(allPositions, it.media.flatMap { m -> m.tracks }) }.orEmpty()
        } catch (_: IOException) {
            // MusicBrainz unreachable: fall back to defaults this time, try again next visit
            return emptyMap()
        }
        lookups.put(LookupDurations(releaseId, ApiJson.encodeToString(durations), System.currentTimeMillis()))
        return durations
    }

    /** null when MusicBrainz has no such entry (404). */
    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String>): T? {
        val url = "https://musicbrainz.org/ws/2/$path".toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
            addQueryParameter("fmt", "json")
        }.build()
        waitForTurn()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> throw IOException("MusicBrainz returned ${response.code}")
                    else -> response.body.string()
                }
            }
        }
        return body?.let { ApiJson.decodeFromString<T>(it) }
    }

    /** MusicBrainz allows one request per second per IP. */
    private suspend fun waitForTurn() = throttle.withLock {
        val wait = lastRequest + MIN_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequest = System.currentTimeMillis()
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1100L
    }
}

/**
 * Matches MusicBrainz tracks to Discogs positions. MusicBrainz track numbers for vinyl usually are the
 * Discogs positions ("A", "B1"); if not, tracks are matched by order when the counts agree.
 */
fun matchDurations(discogsPositions: List<String>, mbTracks: List<MbTrack>): Map<String, Int> {
    val byNumber = mbTracks.associateBy { it.number.trim().uppercase() }
    val allMatchByNumber = discogsPositions.all { it.uppercase() in byNumber }
    return buildMap {
        discogsPositions.forEachIndexed { i, position ->
            val track = when {
                allMatchByNumber -> byNumber[position.uppercase()]
                mbTracks.size == discogsPositions.size -> mbTracks[i]
                else -> null
            }
            val length = track?.length
            if (length != null && length > 0) put(position, (length / 1000.0).roundToInt())
        }
    }
}

@Serializable
data class MbTrack(val number: String = "", val length: Long? = null)

@Serializable
private data class UrlLookup(val relations: List<Relation> = emptyList())

@Serializable
private data class Relation(val release: ReleaseRef? = null)

@Serializable
private data class ReleaseRef(val id: String)

@Serializable
private data class Release(val media: List<Medium> = emptyList())

@Serializable
private data class Medium(val tracks: List<MbTrack> = emptyList())
