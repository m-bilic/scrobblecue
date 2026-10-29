package com.scrobblecue.app.data.discogs

import com.scrobblecue.app.api.apis.DiscogsCollectionControllerApi
import com.scrobblecue.app.api.models.DiscogsRelease
import com.scrobblecue.app.api.models.DiscogsTrack
import com.scrobblecue.app.data.ArtistCredit
import com.scrobblecue.app.data.api.ApiJson
import com.scrobblecue.app.data.api.External
import com.scrobblecue.app.data.api.bodyOrThrow
import com.scrobblecue.app.data.cleanName
import com.scrobblecue.app.data.db.CachedRelease
import com.scrobblecue.app.data.db.CollectionDao
import com.scrobblecue.app.data.db.CollectionItem
import com.scrobblecue.app.data.db.Meta
import com.scrobblecue.app.data.db.MetaDao
import com.scrobblecue.app.data.db.MetaKeys
import com.scrobblecue.app.data.db.ReleaseDao
import com.scrobblecue.app.data.joinArtists
import com.scrobblecue.app.data.searchable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

class DiscogsBusyException : IOException("Discogs is busy (too many requests). Wait a minute and try again.")

/**
 * The user's collection (through the backend, which signs the request) and public release data
 * (directly from Discogs, on the user's own rate limit). Both are cached in the local database.
 */
@Singleton
class DiscogsRepository @Inject constructor(
    private val collectionApi: DiscogsCollectionControllerApi,
    @External private val http: OkHttpClient,
    private val collection: CollectionDao,
    private val releases: ReleaseDao,
    private val meta: MetaDao,
) {
    /**
     * Loads the whole collection and replaces the local copy. Runs once after connecting Discogs, then
     * only when the user refreshes.
     *
     * @return the number of records
     */
    suspend fun syncCollection(onProgress: (page: Int, pages: Int) -> Unit = { _, _ -> }): Int {
        val items = mutableListOf<CollectionItem>()
        var page = 1
        var pages: Int
        do {
            // The backend passes Discogs' JSON through unchanged
            val json = collectionApi.collection(page).bodyOrThrow()
            val data = ApiJson.decodeFromString<CollectionPage>(json)
            pages = data.pagination.pages
            onProgress(page, pages)
            data.releases.mapTo(items) { it.toItem() }
            page++
        } while (page <= pages)

        collection.replaceAll(items)
        meta.put(Meta(MetaKeys.COLLECTION_SYNCED_AT, Instant.now().toString()))
        return items.size
    }

    /** The release as Discogs returns it (JSON), from the cache or from Discogs. */
    suspend fun releaseJson(releaseId: Long): String {
        releases.get(releaseId)?.let { return it.json }
        val json = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("https://api.discogs.com/releases/$releaseId").build()
            http.newCall(request).execute().use { response ->
                if (response.code == 429) throw DiscogsBusyException()
                if (!response.isSuccessful) throw IOException("Discogs returned ${response.code} for release $releaseId")
                response.body.string()
            }
        }
        releases.put(CachedRelease(releaseId, json, System.currentTimeMillis()))
        return json
    }

    suspend fun release(releaseId: Long): DiscogsRelease =
        ApiJson.decodeFromString(releaseJson(releaseId))
}

/** Every track position of the release in order (headings skipped, index tracks expanded). */
fun DiscogsRelease.allPositions(): List<String> = tracks().map { it.position.orEmpty().trim() }

/** Positions Discogs has no length for. */
fun DiscogsRelease.positionsWithoutDuration(): List<String> =
    tracks().filter { it.duration.isNullOrBlank() }.map { it.position.orEmpty().trim() }

private fun DiscogsRelease.tracks(): List<DiscogsTrack> {
    fun visit(tracks: List<DiscogsTrack>?): List<DiscogsTrack> = tracks.orEmpty().flatMap {
        when (it.type) {
            "heading" -> emptyList()
            "index" -> visit(it.subTracks)
            else -> listOf(it)
        }
    }
    return visit(tracklist)
}

// Shape of a Discogs collection page (only the fields we use)

@Serializable
private data class CollectionPage(
    val pagination: Pagination,
    val releases: List<CollectionRelease> = emptyList(),
)

@Serializable
private data class Pagination(val page: Int, val pages: Int)

@Serializable
private data class CollectionRelease(
    val id: Long,
    @SerialName("instance_id") val instanceId: Long,
    @SerialName("date_added") val dateAdded: String = "",
    @SerialName("basic_information") val info: BasicInformation,
) {
    fun toItem(): CollectionItem {
        val title = cleanName(info.title)
        val artist = joinArtists(info.artists.map { ArtistCredit(it.name, it.join) })
        return CollectionItem(
            instanceId = instanceId,
            releaseId = id,
            title = title,
            artist = artist,
            year = info.year,
            formats = info.formats.joinToString(", ") { it.name },
            isVinyl = info.formats.any { it.name == "Vinyl" },
            thumb = info.thumb,
            cover = info.coverImage,
            addedAt = dateAdded,
            searchText = searchable("$artist $title"),
        )
    }
}

@Serializable
private data class BasicInformation(
    val title: String = "",
    val year: Int = 0,
    val thumb: String = "",
    @SerialName("cover_image") val coverImage: String = "",
    val artists: List<Artist> = emptyList(),
    val formats: List<Format> = emptyList(),
)

@Serializable
private data class Artist(val name: String, val join: String? = null)

@Serializable
private data class Format(val name: String)
