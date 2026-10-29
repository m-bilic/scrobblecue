package com.scrobblecue.app.data.credentials

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/** What the app knows about the user's connections. */
sealed interface Session {
    data object Loading : Session
    data object SignedOut : Session

    /** Usernames are null until the backend has been asked (`/v1/me`), e.g. while offline. */
    data class SignedIn(val lastfmUser: String?, val discogsUser: String?) : Session
}

/**
 * Keeps the sealed credential bundle on the device (DataStore, encrypted with [BundleCipher]) together
 * with the usernames it belongs to. This is the only place the app stores credentials.
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: BundleCipher,
) {
    private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("credentials") }

    private val _session = MutableStateFlow<Session>(Session.Loading)
    val session: StateFlow<Session> = _session.asStateFlow()

    @Volatile
    private var bundle: String? = null

    @Volatile
    private var loaded = false

    /**
     * The bundle for the next backend request. Called from OkHttp's threads; the first call reads it
     * from disk.
     */
    fun currentBundle(): String? {
        if (!loaded) runBlocking { load() }
        return bundle
    }

    suspend fun load() {
        if (loaded) return
        val prefs = store.data.first()
        apply(prefs)
    }

    /** Stores a new bundle, received at the end of a connect flow. */
    suspend fun saveBundle(sealed: String) {
        val prefs = store.edit {
            it[BUNDLE] = cipher.encrypt(sealed)
            // The usernames may have changed (e.g. another Last.fm account); /v1/me fills them in again
            it.remove(LASTFM_USER)
            it.remove(DISCOGS_USER)
        }
        apply(prefs)
    }

    suspend fun saveUsers(lastfmUser: String?, discogsUser: String?) {
        val prefs = store.edit { prefs ->
            if (lastfmUser != null) prefs[LASTFM_USER] = lastfmUser else prefs.remove(LASTFM_USER)
            if (discogsUser != null) prefs[DISCOGS_USER] = discogsUser else prefs.remove(DISCOGS_USER)
        }
        apply(prefs)
    }

    suspend fun clear() {
        apply(store.edit { it.clear() })
    }

    private fun apply(prefs: Preferences) {
        bundle = prefs[BUNDLE]?.let(cipher::decrypt)
        loaded = true
        _session.value = if (bundle == null) {
            Session.SignedOut
        } else {
            Session.SignedIn(prefs[LASTFM_USER], prefs[DISCOGS_USER])
        }
    }

    private companion object {
        val BUNDLE = stringPreferencesKey("bundle")
        val LASTFM_USER = stringPreferencesKey("lastfm_user")
        val DISCOGS_USER = stringPreferencesKey("discogs_user")
    }
}
