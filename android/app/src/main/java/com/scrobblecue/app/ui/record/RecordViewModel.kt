package com.scrobblecue.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scrobblecue.app.data.api.ApiException
import com.scrobblecue.app.data.db.CollectionDao
import com.scrobblecue.app.data.db.CollectionItem
import com.scrobblecue.app.data.db.Correction
import com.scrobblecue.app.data.db.CorrectionDao
import com.scrobblecue.app.data.db.Play
import com.scrobblecue.app.data.db.PlayScope
import com.scrobblecue.app.data.db.ScrobbleDao
import com.scrobblecue.app.data.live.LiveRepository
import com.scrobblecue.app.data.scrobble.ScrobbleInput
import com.scrobblecue.app.data.scrobble.Scrobbler
import com.scrobblecue.app.data.tracklist.Group
import com.scrobblecue.app.data.tracklist.Track
import com.scrobblecue.app.data.tracklist.Tracklist
import com.scrobblecue.app.data.tracklist.TracklistRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant

data class RecordUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val item: CollectionItem? = null,
    val tracklist: Tracklist? = null,
    /** Positions ticked for "Scrobble selected". */
    val selected: Set<String> = emptySet(),
    /** How long ago the record finished playing. */
    val endedMinutesAgo: Int = 0,
    val editing: Boolean = false,
    val busy: Boolean = false,
    val scrobbleError: String? = null,
)

@HiltViewModel(assistedFactory = RecordViewModel.Factory::class)
class RecordViewModel @AssistedInject constructor(
    @Assisted private val releaseId: Long,
    private val tracklists: TracklistRepository,
    private val collection: CollectionDao,
    private val corrections: CorrectionDao,
    private val scrobbler: Scrobbler,
    private val live: LiveRepository,
    scrobbles: ScrobbleDao,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(releaseId: Long): RecordViewModel
    }

    private val _state = MutableStateFlow(RecordUiState())
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    private val playId = MutableStateFlow<String?>(null)

    /** The last scrobble from this page, updated live while the queue sends it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lastPlay: StateFlow<Play?> = playId
        .flatMapLatest { id -> if (id == null) flowOf(null) else scrobbles.observePlay(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.tracklist == null, error = null) }
            try {
                val item = collection.byRelease(releaseId)
                val tracklist = tracklists.prepare(releaseId)
                _state.update { it.copy(loading = false, item = item, tracklist = tracklist) }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, error = "Could not load this record – are you online? (${e.message})") }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun toggle(position: String) = _state.update {
        it.copy(selected = if (position in it.selected) it.selected - position else it.selected + position)
    }

    fun setEndedMinutesAgo(minutes: Int) = _state.update { it.copy(endedMinutesAgo = minutes) }

    fun toggleEditing() = _state.update { it.copy(editing = !it.editing) }

    fun scrobbleSide(side: String, tracks: List<Track>) = scrobble(PlayScope.SIDE, side, tracks)

    fun scrobbleWhole() {
        val tracklist = _state.value.tracklist ?: return
        scrobble(PlayScope.WHOLE, null, tracklist.groups.flatMap { it.tracks })
    }

    fun scrobbleSelected() {
        val current = _state.value
        val tracks = current.tracklist?.allTracks.orEmpty().filter { it.position in current.selected }
        scrobble(PlayScope.TRACKS, null, tracks)
    }

    private fun scrobble(scope: PlayScope, side: String?, tracks: List<Track>) {
        val current = _state.value
        val tracklist = current.tracklist ?: return
        if (current.busy || tracks.isEmpty()) return
        _state.update { it.copy(busy = true, scrobbleError = null) }
        viewModelScope.launch {
            try {
                playId.value = scrobbler.scrobble(
                    ScrobbleInput(
                        tracklist = tracklist,
                        cover = current.item?.cover.orEmpty(),
                        scope = scope,
                        side = side,
                        tracks = tracks,
                        endedAt = Instant.now().minusSeconds(current.endedMinutesAgo * 60L),
                    ),
                )
                _state.update { it.copy(busy = false, selected = emptySet()) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, scrobbleError = "Scrobbling failed: ${e.message}") }
            }
        }
    }

    /** Live mode: the side starts now; tracks are scrobbled as they finish (see LiveBar). */
    fun playLive(side: Group) {
        val current = _state.value
        val tracklist = current.tracklist ?: return
        if (current.busy) return
        _state.update { it.copy(busy = true, scrobbleError = null) }
        viewModelScope.launch {
            try {
                live.start(tracklist, side, current.item?.cover.orEmpty())
                _state.update { it.copy(busy = false) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, scrobbleError = "Could not start live mode: ${e.message}") }
            }
        }
    }

    /** After the notification permission was granted: show the notification for the playing side. */
    fun refreshLive() {
        viewModelScope.launch { live.tick() }
    }

    /** Saves the user's fix for a track and prepares the record again with it. */
    fun correct(track: Track, durationSec: Int?, skip: Boolean) {
        viewModelScope.launch {
            corrections.put(Correction(releaseId = releaseId, position = track.position, durationSec = durationSec, skip = skip))
            load()
        }
    }
}
