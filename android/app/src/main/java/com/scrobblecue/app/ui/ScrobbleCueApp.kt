package com.scrobblecue.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.scrobblecue.app.data.credentials.Session
import com.scrobblecue.app.data.credentials.SessionRepository
import com.scrobblecue.app.data.live.LiveRepository
import com.scrobblecue.app.ui.collection.CollectionScreen
import com.scrobblecue.app.ui.connect.ConnectScreen
import com.scrobblecue.app.ui.record.RecordScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Screens of the signed-in part of the app. */
@Serializable
object CollectionRoute

@Serializable
data class RecordRoute(val releaseId: Long)

@HiltViewModel
class AppViewModel @Inject constructor(sessions: SessionRepository, live: LiveRepository) : ViewModel() {
    val session = sessions.session

    init {
        viewModelScope.launch { sessions.load() }
        // A side that was playing live when the app was closed continues (or asks "flip?")
        viewModelScope.launch { live.resume() }
    }
}

/**
 * Until both accounts are connected the app shows the connect screen; then the collection, and a record
 * page for each record.
 */
@Composable
fun ScrobbleCueApp(viewModel: AppViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    when (val current = session) {
        Session.Loading -> Box(Modifier.fillMaxSize())
        Session.SignedOut -> ConnectScreen(lastfmUser = null)
        is Session.SignedIn ->
            if (current.discogsUser == null) ConnectScreen(lastfmUser = current.lastfmUser ?: "")
            else SignedInNavigation(current)
    }
}

@Composable
private fun SignedInNavigation(session: Session.SignedIn) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = CollectionRoute) {
        composable<CollectionRoute> {
            CollectionScreen(
                lastfmUser = session.lastfmUser.orEmpty(),
                onOpenRecord = { navController.navigate(RecordRoute(it)) },
            )
        }
        composable<RecordRoute> { entry ->
            RecordScreen(
                releaseId = entry.toRoute<RecordRoute>().releaseId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
