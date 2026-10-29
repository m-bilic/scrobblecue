package com.scrobblecue.app.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scrobblecue.app.data.api.ApiException
import com.scrobblecue.app.data.credentials.SessionRepository
import com.scrobblecue.app.data.db.CollectionDao
import com.scrobblecue.app.data.db.CollectionItem
import com.scrobblecue.app.data.db.MetaDao
import com.scrobblecue.app.data.db.MetaKeys
import com.scrobblecue.app.data.discogs.DiscogsRepository
import com.scrobblecue.app.data.searchable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

data class SyncState(
    val running: Boolean = false,
    /** "Page 2 of 3" while loading. */
    val progress: String? = null,
    val error: String? = null,
)

@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val discogs: DiscogsRepository,
    private val sessions: SessionRepository,
    collection: CollectionDao,
    meta: MetaDao,
) : ViewModel() {

    val query = MutableStateFlow("")

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    val syncedAt: StateFlow<Instant?> = meta.observe(MetaKeys.COLLECTION_SYNCED_AT)
        .map { it?.let(Instant::parse) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val all = collection.all()

    val total: StateFlow<Int> = all.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Records matching the search (artist or title, ignoring case and accents). */
    val records: StateFlow<List<CollectionItem>> = combine(all, query) { items, q ->
        val words = searchable(q).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) items else items.filter { item -> words.all { it in item.searchText } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // The first sync starts by itself after connecting Discogs; later ones only when the user asks
        viewModelScope.launch {
            if (meta.observe(MetaKeys.COLLECTION_SYNCED_AT).first() == null) refresh()
        }
    }

    fun refresh() {
        if (_sync.value.running) return
        _sync.value = SyncState(running = true)
        viewModelScope.launch {
            val error = try {
                discogs.syncCollection { page, pages -> _sync.value = SyncState(true, "Page $page of $pages") }
                null
            } catch (e: IOException) {
                "Could not load your collection – are you online? (${e.message})"
            } catch (e: ApiException) {
                if (e.needsReconnect) sessions.refreshUsers()
                e.message
            }
            _sync.value = SyncState(error = error)
        }
    }

    fun logOut() {
        viewModelScope.launch { sessions.logOut() }
    }

    fun clearAllData() {
        viewModelScope.launch { sessions.clearAllData() }
    }
}
