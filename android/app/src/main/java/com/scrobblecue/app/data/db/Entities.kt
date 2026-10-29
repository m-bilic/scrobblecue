package com.scrobblecue.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Everything ScrobbleCue knows about the user lives in this database on the phone. The server stores
 * nothing. "Clear data" empties it. Same tables as the website's IndexedDB (web/src/store/db.ts).
 */

/** One record in the user's Discogs collection. */
@Entity(tableName = "collection", indices = [Index("releaseId")])
data class CollectionItem(
    @PrimaryKey val instanceId: Long,
    val releaseId: Long,
    val title: String,
    val artist: String,
    val year: Int,
    /** Format names joined with ", " (e.g. "Vinyl, LP"). */
    val formats: String,
    val isVinyl: Boolean,
    val thumb: String,
    val cover: String,
    val addedAt: String,
    /** Lower-case, accent-free "artist title" for searching. */
    val searchText: String,
)

/** Raw Discogs release JSON, cached after first use. */
@Entity(tableName = "releases")
data class CachedRelease(
    @PrimaryKey val id: Long,
    val json: String,
    val fetchedAt: Long,
)

/** The last tracklist the backend prepared for a release (JSON), so records open offline too. */
@Entity(tableName = "prepared")
data class PreparedRelease(
    @PrimaryKey val releaseId: Long,
    val json: String,
    val preparedAt: Long,
)

/** Track lengths found on MusicBrainz, by Discogs position (JSON object). Also cached when empty. */
@Entity(tableName = "lookups")
data class LookupDurations(
    @PrimaryKey val releaseId: Long,
    val durationsJson: String,
    val checkedAt: Long,
)

/** A user's fix for one track, reused on later plays of the record. */
@Entity(tableName = "corrections", primaryKeys = ["releaseId", "position"])
data class Correction(
    val releaseId: Long,
    val position: String,
    val title: String? = null,
    val artist: String? = null,
    val durationSec: Int? = null,
    val skip: Boolean = false,
)

enum class PlayScope { SIDE, WHOLE, TRACKS }

enum class PlayStatus { SENDING, DONE, WAITING }

/** Instant: the record was already played. Live: tracks are scrobbled as they finish playing. */
enum class PlayMode { INSTANT, LIVE }

/** One listening session in the local play log. */
@Entity(tableName = "plays", indices = [Index("releaseId")])
data class Play(
    @PrimaryKey val id: String,
    val releaseId: Long,
    val album: String,
    val albumArtist: String,
    val cover: String,
    val scope: PlayScope,
    val side: String?,
    val endedAt: String,
    val createdAt: Long,
    val status: PlayStatus,
    val accepted: Int = 0,
    val ignored: Int = 0,
    val message: String? = null,
    @ColumnInfo(defaultValue = "INSTANT") val mode: PlayMode = PlayMode.INSTANT,
)

enum class QueueState {
    PENDING,

    /** A send ended without a clear answer: Last.fm is checked before retrying (SPEC.md D10). */
    UNCERTAIN,
}

/** A scrobble not yet confirmed by Last.fm. */
@Entity(tableName = "queue", indices = [Index("playId")])
data class QueuedScrobble(
    @PrimaryKey val id: String,
    val playId: String,
    val seq: Int,
    val artist: String,
    val track: String,
    val album: String,
    val albumArtist: String?,
    val durationSec: Int,
    /** ISO-8601, whole seconds (e.g. 2026-10-28T19:00:00Z). */
    val timestamp: String,
    val state: QueueState,
    /** Live mode: not sent before the track has ended (same format as [timestamp]). null = right away. */
    val sendAfter: String? = null,
)

@Entity(tableName = "meta")
data class Meta(
    @PrimaryKey val key: String,
    val value: String,
)
