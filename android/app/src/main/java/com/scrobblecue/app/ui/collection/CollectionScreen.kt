package com.scrobblecue.app.ui.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.scrobblecue.app.R
import com.scrobblecue.app.data.db.CollectionItem
import com.scrobblecue.app.data.timeAgo
import com.scrobblecue.app.ui.footer.Attribution
import com.scrobblecue.app.ui.live.LiveBar

/** The user's Discogs collection as a cover grid, with search and pull-to-refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    lastfmUser: String,
    onOpenRecord: (releaseId: Long) -> Unit,
    viewModel: CollectionViewModel = hiltViewModel(),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val syncedAt by viewModel.syncedAt.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ScrobbleCue") },
                actions = { AccountMenu(lastfmUser, viewModel::logOut, viewModel::clearAllData) },
            )
        },
        bottomBar = { LiveBar() },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = sync.running,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { viewModel.query.value = it },
                            placeholder = { Text("Search artist or title") },
                            singleLine = true,
                            // Results update while typing; the search key only closes the keyboard
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val status = when {
                            sync.progress != null -> "Loading your collection… ${sync.progress}"
                            sync.running -> "Loading your collection…"
                            syncedAt != null -> "$total records · synced ${timeAgo(syncedAt!!)} · pull down to refresh"
                            else -> ""
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                items(records, key = { it.instanceId }) { record ->
                    RecordCard(record, onClick = {
                        keyboard?.hide()
                        onOpenRecord(record.releaseId)
                    })
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Attribution() }
            }
        }
    }
}

@Composable
private fun RecordCard(record: CollectionItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = record.cover.ifEmpty { record.thumb },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            record.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            record.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AccountMenu(lastfmUser: String, onLogOut: () -> Unit, onClearData: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    IconButton(onClick = { open = true }) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Account")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (lastfmUser.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text("$lastfmUser on Last.fm") },
                onClick = {
                    open = false
                    uriHandler.openUri("https://www.last.fm/user/$lastfmUser")
                },
            )
        }
        DropdownMenuItem(text = { Text("Log out") }, onClick = { open = false; onLogOut() })
        DropdownMenuItem(text = { Text("Clear data") }, onClick = { open = false; confirmClear = true })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear data?") },
            text = { Text("Deletes everything ScrobbleCue stored on this phone (collection, corrections, play log, unsent scrobbles) and logs you out.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearData() }) { Text("Clear data") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
