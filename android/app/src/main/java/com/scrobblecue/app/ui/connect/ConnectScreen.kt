package com.scrobblecue.app.ui.connect

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.scrobblecue.app.data.credentials.Provider
import com.scrobblecue.app.data.credentials.SessionRepository
import com.scrobblecue.app.ui.footer.Attribution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(private val sessions: SessionRepository) : ViewModel() {
    private val startError = MutableStateFlow<String?>(null)

    /** Error from the last attempt: starting it here, or the result coming back from the browser. */
    val error = combine(startError, sessions.authError) { start, auth -> start ?: auth }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Gets the provider's sign-in page from the backend and hands it to [open]. */
    fun connect(provider: Provider, open: (url: String) -> Unit) {
        startError.value = null
        viewModelScope.launch {
            try {
                open(sessions.authorizeUrl(provider))
            } catch (e: Exception) {
                startError.value = "Could not start connecting: ${e.message}"
            }
        }
    }
}

/** Step 1: connect Last.fm. Step 2: connect Discogs. */
@Composable
fun ConnectScreen(lastfmUser: String?, viewModel: ConnectViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val error by viewModel.error.collectAsStateWithLifecycle()
    // A Custom Tab: the provider's real page, in the user's browser with their logins
    val openInBrowser = { url: String -> CustomTabsIntent.Builder().build().launchUrl(context, url.toUri()) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Scrobble your records", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Pick a record from your Discogs collection, tap a side, and it's on your Last.fm. " +
                    "No account, and nothing is stored on our server.",
                style = MaterialTheme.typography.bodyLarge,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Step("1. Connect Last.fm") {
                if (lastfmUser != null) {
                    Text(if (lastfmUser.isEmpty()) "Connected." else "Connected as $lastfmUser.")
                } else {
                    Button(onClick = { viewModel.connect(Provider.LASTFM, openInBrowser) }) { Text("Connect Last.fm") }
                }
            }
            Step("2. Connect Discogs") {
                Text("ScrobbleCue only reads your collection.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { viewModel.connect(Provider.DISCOGS, openInBrowser) },
                    enabled = lastfmUser != null,
                ) { Text("Connect Discogs") }
            }
            Attribution()
        }
    }
}

@Composable
private fun Step(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
