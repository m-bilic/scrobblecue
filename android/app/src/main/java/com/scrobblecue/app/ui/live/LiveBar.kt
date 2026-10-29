package com.scrobblecue.app.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.scrobblecue.app.data.formatDuration
import com.scrobblecue.app.data.live.LiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class LiveViewModel @Inject constructor(private val live: LiveRepository) : ViewModel() {
    val session = live.session
    val error = MutableStateFlow<String?>(null)

    fun stop() = run { live.stop() }
    fun startNextSide() = run { live.startNextSide() }
    fun done() = run { live.end() }

    private fun run(action: suspend () -> Unit) {
        error.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }
}

/** Shown at the bottom of the screens while a side plays live: current track, Stop, and the flip prompt. */
@Composable
fun LiveBar(viewModel: LiveViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val current = session ?: return
    // Redraws every second for the countdown and the progress bar
    val now by produceState(Instant.now(), current) {
        while (true) {
            value = Instant.now()
            delay(1_000)
        }
    }

    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            val start = current.start
            val end = current.end
            if (start != null && end != null) {
                val total = Duration.between(start, end).seconds.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { (Duration.between(start, now).seconds.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (current.finished(now)) {
                    val next = current.nextSide()
                    Column(Modifier.weight(1f)) {
                        Text("Side ${current.side} is done.", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (next != null) "Flip to side ${next.label}?" else "That was the last side.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (next != null) Button(onClick = viewModel::startNextSide) { Text("Start side ${next.label}") }
                    OutlinedButton(onClick = viewModel::done) { Text("Done") }
                } else {
                    val track = current.currentTrack(now)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${current.tracklist.album} · Side ${current.side}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (track != null) {
                            Text(
                                "${track.position} · ${track.title}",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val left = Duration.between(now, track.end).seconds.coerceAtLeast(0).toInt()
                            Text(
                                "${formatDuration(left)} left" + if (track.scrobble) "" else " · not scrobbled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // The record may be a little ahead of the estimated lengths: allow flipping early
                    val next = current.nextSide()
                    if (next != null && current.onLastTrack(now)) {
                        Button(onClick = viewModel::startNextSide) { Text("Side ${next.label}") }
                    }
                    OutlinedButton(onClick = viewModel::stop) { Text("Stop") }
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            }
        }
    }
}
