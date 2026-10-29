package com.scrobblecue.app.data.credentials

import android.net.Uri
import com.scrobblecue.app.api.apis.DiscogsAuthControllerApi
import com.scrobblecue.app.api.apis.LastFmAuthControllerApi
import com.scrobblecue.app.api.apis.MeControllerApi
import com.scrobblecue.app.api.models.StartRequest
import com.scrobblecue.app.data.api.ApiException
import com.scrobblecue.app.data.api.bodyOrThrow
import com.scrobblecue.app.data.db.AppDatabase
import com.scrobblecue.app.data.live.LiveRepository
import com.scrobblecue.app.work.QueueScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class Provider { LASTFM, DISCOGS }

/**
 * Connecting Last.fm and Discogs (SPEC.md flows 8.1 and 8.2). The app opens the provider's page in a
 * browser tab; at the end the backend sends the browser to the app's sign-in link with the sealed bundle
 * in the URL fragment (`#bundle=…`), which [receive] stores.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val credentials: CredentialStore,
    private val lastFmAuth: LastFmAuthControllerApi,
    private val discogsAuth: DiscogsAuthControllerApi,
    private val me: MeControllerApi,
    private val database: AppDatabase,
    private val scheduler: QueueScheduler,
    private val live: LiveRepository,
) {
    val session: StateFlow<Session> = credentials.session

    private val _authError = MutableStateFlow<String?>(null)

    /** Message for the connect screen when the last connect attempt failed. */
    val authError: StateFlow<String?> = _authError.asStateFlow()

    suspend fun load() {
        credentials.load()
        if (session.value is Session.SignedIn) refreshUsers()
    }

    /** The page to open in a browser tab to connect [provider]. */
    suspend fun authorizeUrl(provider: Provider): String {
        _authError.value = null
        val request = StartRequest(StartRequest.Client.ANDROID)
        val response = when (provider) {
            Provider.LASTFM -> lastFmAuth.lastFmStart(request)
            Provider.DISCOGS -> discogsAuth.discogsStart(request)
        }
        return response.bodyOrThrow().authorizeUrl ?: throw IOException("No sign-in address from the server")
    }

    /**
     * Handles the sign-in link: `…#bundle=<sealed>` on success, `…#error=<code>` on failure.
     *
     * @return true when the link was a sign-in result
     */
    suspend fun receive(link: Uri): Boolean {
        val fragment = link.encodedFragment ?: return false
        val params = fragment.split('&').associate {
            val (key, value) = it.split('=', limit = 2).let { parts -> parts[0] to parts.getOrElse(1) { "" } }
            key to Uri.decode(value)
        }
        val bundle = params["bundle"]
        val error = params["error"]
        when {
            bundle != null -> {
                credentials.saveBundle(bundle)
                refreshUsers()
                // Scrobbles that waited for a reconnect can go now
                scheduler.sendWhenOnline()
            }
            error != null -> _authError.value = AUTH_ERRORS[error] ?: "Connecting failed ($error). Please try again."
            else -> return false
        }
        return true
    }

    /** Asks the backend which accounts the bundle belongs to. Offline, the stored names stay. */
    suspend fun refreshUsers() {
        try {
            val users = me.me().bodyOrThrow()
            credentials.saveUsers(users.lastfmUser, users.discogsUser)
        } catch (_: IOException) {
            // Offline: keep what we know
        } catch (e: ApiException) {
            if (e.needsReconnect) {
                credentials.clear()
                _authError.value = "Your sign-in has expired. Please connect again."
            }
        }
    }

    /** Forgets the credentials. The collection and play log stay on the phone. */
    suspend fun logOut() {
        credentials.clear()
    }

    /** Deletes everything ScrobbleCue stored on this phone, including unsent scrobbles. */
    suspend fun clearAllData() {
        live.stop()
        scheduler.cancel()
        withContext(Dispatchers.IO) { database.clearAllTables() }
        credentials.clear()
    }

    private companion object {
        val AUTH_ERRORS = mapOf(
            "lastfm-denied" to "Last.fm access was not allowed.",
            "lastfm-expired" to "Connecting Last.fm took too long. Please try again.",
            "lastfm-failed" to "Connecting Last.fm failed. Please try again.",
            "discogs-denied" to "Discogs access was not allowed.",
            "discogs-expired" to "Connecting Discogs took too long. Please try again.",
            "discogs-failed" to "Connecting Discogs failed. Please try again.",
        )
    }
}
