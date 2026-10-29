package com.scrobblecue.app.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.scrobblecue.app.R
import com.scrobblecue.app.api.models.PreparedTrack
import com.scrobblecue.app.api.models.TrackGroup
import com.scrobblecue.app.data.db.Play
import com.scrobblecue.app.data.db.PlayStatus
import com.scrobblecue.app.data.formatDuration
import com.scrobblecue.app.data.parseDuration
import com.scrobblecue.app.data.tracklist.Group
import com.scrobblecue.app.data.tracklist.Track
import com.scrobblecue.app.ui.live.LiveBar

private val ENDED_OPTIONS = listOf(0 to "just now", 5 to "5 min ago", 15 to "15 min ago", 30 to "30 min ago", 60 to "1 h ago")

/** One record: sides with a "Scrobble side A" button each, track selection and corrections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    releaseId: Long,
    onBack: () -> Unit,
    viewModel: RecordViewModel = hiltViewModel<RecordViewModel, RecordViewModel.Factory>(
        key = releaseId.toString(),
        creationCallback = { it.create(releaseId) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastPlay by viewModel.lastPlay.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.refreshLive()
    }
    // Live mode shows the current track in a notification; Android 13+ asks the user first
    val playLive = { side: Group ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.playLive(side)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // The header below shows the title; the bar only holds the back button
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = { LiveBar() },
    ) { padding ->
        val tracklist = state.tracklist
        when {
            state.loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            tracklist == null -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text(state.error ?: "Could not load this record.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text("Try again") }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(state) }
                item { Controls(state, viewModel) }
                val estimated = tracklist.allTracks.count { it.estimated }
                if (estimated > 0 && !state.editing) {
                    item {
                        Text(
                            "$estimated track length${if (estimated > 1) "s are" else " is"} unknown and estimated as 3:30 (marked ~). " +
                                "Use \"Edit tracks\" to correct them – ScrobbleCue remembers it on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.scrobbleError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                lastPlay?.let { item { PlayResult(it) } }
                tracklist.groups.forEach { group -> side(group, tracklist.sideScrobblable, state, viewModel, playLive) }
                if (tracklist.unassigned.isNotEmpty()) {
                    side(Group(TrackGroup.Kind.ALL, "Other", tracklist.unassigned), false, state, viewModel, playLive)
                }
            }
        }
    }
}

@Composable
private fun Header(state: RecordUiState) {
    val tracklist = state.tracklist ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AsyncImage(
            model = state.item?.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tracklist.album, style = MaterialTheme.typography.titleLarge)
            Text(tracklist.albumArtist, style = MaterialTheme.typography.titleMedium)
            state.item?.let { item ->
                Text(
                    listOfNotNull(item.year.takeIf { it > 0 }?.toString(), item.formats.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Controls(state: RecordUiState, viewModel: RecordViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Finished", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ENDED_OPTIONS.forEach { (minutes, label) ->
                FilterChip(
                    selected = state.endedMinutesAgo == minutes,
                    onClick = { viewModel.setEndedMinutesAgo(minutes) },
                    label = { Text(label) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = viewModel::scrobbleWhole, enabled = !state.busy) { Text("Scrobble whole record") }
            FilledTonalButton(onClick = viewModel::scrobbleSelected, enabled = !state.busy && state.selected.isNotEmpty()) {
                Text("Selected (${state.selected.size})")
            }
            TextButton(onClick = viewModel::toggleEditing) { Text(if (state.editing) "Done editing" else "Edit tracks") }
        }
    }
}

@Composable
private fun PlayResult(play: Play) {
    val (text, error) = when (play.status) {
        PlayStatus.DONE -> {
            val tracks = if (play.accepted == 1) "track" else "tracks"
            val ignored = if (play.ignored > 0) " (${play.ignored} ignored by Last.fm)" else ""
            (play.message ?: "Scrobbled ${play.accepted} $tracks to Last.fm$ignored.") to false
        }
        PlayStatus.SENDING -> "Sending to Last.fm…" to false
        PlayStatus.WAITING -> (play.message ?: "Saved – will be sent to Last.fm shortly.") to true
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(12.dp))
    }
}

private fun LazyListScope.side(
    group: Group,
    sideScrobblable: Boolean,
    state: RecordUiState,
    viewModel: RecordViewModel,
    playLive: (Group) -> Unit,
) {
    item(key = "head-${group.label}") {
        val title = when {
            group.kind == TrackGroup.Kind.SIDE -> "Side ${group.label}"
            group.kind == TrackGroup.Kind.DISC -> "Disc ${group.label}"
            group.label == "ALL" -> "Tracks"
            else -> group.label
        }
        Column {
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "  ${formatDuration(group.totalSec)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sideScrobblable && group.kind == TrackGroup.Kind.SIDE) {
                // "Scrobble" = just finished playing; "Play live" = starting now, scrobbled track by track
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = { playLive(group) }, enabled = !state.busy) { Text("Play live") }
                    Button(onClick = { viewModel.scrobbleSide(group.label, group.tracks) }, enabled = !state.busy) {
                        Text("Scrobble side ${group.label}")
                    }
                }
            }
        }
    }
    items(group.tracks, key = { "${group.label}-${it.position}" }) { track ->
        TrackRow(
            track = track,
            albumArtist = state.tracklist?.albumArtist.orEmpty(),
            checked = track.position in state.selected,
            editing = state.editing,
            onToggle = { viewModel.toggle(track.position) },
            onCorrect = { duration, skip -> viewModel.correct(track, duration, skip) },
        )
    }
}

@Composable
private fun TrackRow(
    track: Track,
    albumArtist: String,
    checked: Boolean,
    editing: Boolean,
    onToggle: () -> Unit,
    onCorrect: (durationSec: Int?, skip: Boolean) -> Unit,
) {
    // Skipped by the user (not just too short to count)
    val skipped = !track.scrobblable && track.durationSec > 30
    val textColor = if (track.scrobblable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = track.scrobblable)
        Text(track.position, style = MaterialTheme.typography.labelLarge, color = textColor, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Only when it differs from the album artist (compilations, features)
            if (track.artist.isNotEmpty() && track.artist != albumArtist) {
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (!editing) {
            Text(
                (if (track.estimated) "~" else "") + formatDuration(track.durationSec),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
        }
    }
    if (editing) {
        var length by remember(track.durationSec) { mutableStateOf(formatDuration(track.durationSec)) }
        fun save(skip: Boolean) {
            val seconds = parseDuration(length)
            val duration = when {
                seconds != null && seconds != track.durationSec -> seconds
                track.durationSource == PreparedTrack.DurationSource.CORRECTION -> track.durationSec
                else -> null
            }
            if (duration != null || skip != skipped) onCorrect(duration, skip)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 84.dp),
        ) {
            OutlinedTextField(
                value = length,
                onValueChange = { length = it },
                label = { Text("Length") },
                singleLine = true,
                isError = parseDuration(length) == null,
                // Ascii, not Number: number keyboards have no ":" for "3:45"
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save(skipped) }),
                modifier = Modifier
                    .width(112.dp)
                    .onFocusChanged { if (!it.isFocused && length != formatDuration(track.durationSec)) save(skipped) },
            )
            Checkbox(checked = skipped, onCheckedChange = { save(it) })
            Text("Skip")
        }
    }
}
