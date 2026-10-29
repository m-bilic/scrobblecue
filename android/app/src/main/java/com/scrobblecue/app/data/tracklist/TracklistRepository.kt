package com.scrobblecue.app.data.tracklist

import com.scrobblecue.app.api.apis.TracklistControllerApi
import com.scrobblecue.app.api.models.PrepareRequest
import com.scrobblecue.app.api.models.PreparedTrack
import com.scrobblecue.app.api.models.PreparedTracklist
import com.scrobblecue.app.api.models.TrackCorrection
import com.scrobblecue.app.api.models.TrackGroup
import com.scrobblecue.app.data.api.ApiJson
import com.scrobblecue.app.data.api.bodyOrThrow
import com.scrobblecue.app.data.db.CorrectionDao
import com.scrobblecue.app.data.db.PreparedRelease
import com.scrobblecue.app.data.db.ReleaseDao
import com.scrobblecue.app.data.discogs.DiscogsRepository
import com.scrobblecue.app.data.discogs.allPositions
import com.scrobblecue.app.data.discogs.positionsWithoutDuration
import com.scrobblecue.app.data.musicbrainz.MusicBrainzClient
import kotlinx.serialization.Serializable
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A record ready to show and scrobble: tracks grouped by side, names cleaned, lengths filled in. */
@Serializable
data class Tracklist(
    val releaseId: Long,
    val album: String,
    val albumArtist: String,
    /** Side buttons make sense (vinyl with A/B… positions). */
    val sideScrobblable: Boolean,
    val groups: List<Group>,
    /** Tracks whose position fits no side. */
    val unassigned: List<Track>,
) {
    val allTracks: List<Track> get() = groups.flatMap { it.tracks } + unassigned
}

@Serializable
data class Group(val kind: TrackGroup.Kind, val label: String, val tracks: List<Track>) {
    val totalSec: Int get() = tracks.sumOf { it.durationSec }
}

@Serializable
data class Track(
    val position: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val durationSource: PreparedTrack.DurationSource,
    /** false for skipped tracks and tracks under 31 seconds (Last.fm ignores those). */
    val scrobblable: Boolean,
) {
    val estimated: Boolean get() = durationSource == PreparedTrack.DurationSource.DEFAULT
}

/**
 * Prepares a record for display (SPEC.md flow 8.4): the Discogs release, missing lengths from
 * MusicBrainz and the user's corrections go to the backend, which applies the tracklist rules.
 */
@Singleton
class TracklistRepository @Inject constructor(
    private val discogs: DiscogsRepository,
    private val musicBrainz: MusicBrainzClient,
    private val corrections: CorrectionDao,
    private val releases: ReleaseDao,
    private val api: TracklistControllerApi,
) {
    /**
     * Prepares the record online and remembers the result. Offline, the last prepared version is used,
     * so records opened before can be scrobbled in airplane mode.
     */
    suspend fun prepare(releaseId: Long): Tracklist = try {
        prepareOnline(releaseId).also {
            releases.putPrepared(PreparedRelease(releaseId, ApiJson.encodeToString(it), System.currentTimeMillis()))
        }
    } catch (e: IOException) {
        releases.prepared(releaseId)?.let { ApiJson.decodeFromString<Tracklist>(it.json) } ?: throw e
    }

    private suspend fun prepareOnline(releaseId: Long): Tracklist {
        val release = discogs.release(releaseId)
        // Only ask MusicBrainz when Discogs is missing lengths
        val durations = if (release.positionsWithoutDuration().isNotEmpty()) {
            musicBrainz.lookupDurations(releaseId, release.allPositions())
        } else {
            emptyMap()
        }
        val fixes = corrections.forRelease(releaseId).map {
            TrackCorrection(position = it.position, artist = it.artist, durationSec = it.durationSec, skip = it.skip, title = it.title)
        }
        val prepared = api.prepare(PrepareRequest(release = release, corrections = fixes, durations = durations)).bodyOrThrow()
        return prepared.toTracklist(releaseId)
    }
}

private fun PreparedTracklist.toTracklist(releaseId: Long) = Tracklist(
    releaseId = this.releaseId ?: releaseId,
    album = album.orEmpty(),
    albumArtist = albumArtist.orEmpty(),
    sideScrobblable = sideScrobblable ?: false,
    groups = groups.orEmpty().map { group ->
        Group(group.kind ?: TrackGroup.Kind.ALL, group.label.orEmpty(), group.tracks.orEmpty().map { it.toTrack() })
    },
    unassigned = unassigned.orEmpty().map { it.toTrack() },
)

private fun PreparedTrack.toTrack() = Track(
    position = position.orEmpty(),
    title = title.orEmpty(),
    artist = artist.orEmpty(),
    durationSec = durationSec ?: 0,
    durationSource = durationSource ?: PreparedTrack.DurationSource.DEFAULT,
    scrobblable = scrobblable ?: false,
)
