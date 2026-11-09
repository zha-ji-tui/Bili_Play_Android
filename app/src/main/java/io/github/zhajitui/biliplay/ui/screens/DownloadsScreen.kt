package io.github.zhajitui.biliplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.zhajitui.biliplay.R
import io.github.zhajitui.biliplay.data.api.BiliText
import io.github.zhajitui.biliplay.data.download.AudioDownloadManager
import io.github.zhajitui.biliplay.data.download.DownloadSort
import io.github.zhajitui.biliplay.data.download.DownloadedTrack
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.ui.components.EmptyState
import java.text.Collator
import java.util.Locale

@Composable
fun DownloadsScreen(
    downloadManager: AudioDownloadManager,
    onPlay: (List<Track>, Int) -> Unit,
) {
    val downloads by downloadManager.downloads.collectAsStateWithLifecycle()
    val ready by downloadManager.ready.collectAsStateWithLifecycle()
    var sortOrder by remember { mutableStateOf(downloadManager.sortOrder()) }
    var sortMenuVisible by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DownloadedTrack?>(null) }

    LaunchedEffect(downloadManager) {
        downloadManager.enrichMissingMetadata()
    }

    val sorted = remember(downloads, sortOrder) { sortDownloads(downloads, sortOrder) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.downloads_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            Box {
                TextButton(onClick = { sortMenuVisible = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(sortOrder.labelRes()))
                }
                DropdownMenu(
                    expanded = sortMenuVisible,
                    onDismissRequest = { sortMenuVisible = false },
                ) {
                    DownloadSort.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(stringResource(order.labelRes())) },
                            onClick = {
                                sortMenuVisible = false
                                sortOrder = order
                                downloadManager.setSortOrder(order)
                            },
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.downloads_summary, sorted.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (!ready) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (sorted.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Download,
                title = stringResource(R.string.downloads_title),
                message = stringResource(R.string.downloads_empty),
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 48.dp),
            )
        } else {
            FilledTonalButton(
                onClick = { onPlay(sorted.map { it.track }, 0) },
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.downloads_play_all))
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    sorted,
                    key = { _, item -> item.track.bvid },
                ) { index, item ->
                    DownloadCard(
                        item = item,
                        onClick = { onPlay(sorted.map { it.track }, index) },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.downloads_delete_title)) },
            text = { Text(stringResource(R.string.downloads_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        downloadManager.delete(item.track.bvid)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadCard(
    item: DownloadedTrack,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.track.cover.isNotEmpty()) {
                AsyncImage(
                    model = item.track.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.track.uploader.ifBlank { stringResource(R.string.downloads_hint_short) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = BiliText.formatDuration(item.track.duration),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.downloads_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun sortDownloads(
    downloads: List<DownloadedTrack>,
    order: DownloadSort,
): List<DownloadedTrack> {
    val collator = Collator.getInstance(Locale.CHINA)
    return when (order) {
        DownloadSort.Recent -> downloads.sortedByDescending { it.savedAt }
        DownloadSort.Oldest -> downloads.sortedBy { it.savedAt }
        DownloadSort.Title -> downloads.sortedWith(
            compareBy(collator) { it.track.title },
        )
        DownloadSort.Uploader -> downloads.sortedWith(
            compareBy(collator) { it.track.uploader.ifBlank { it.track.title } },
        )
    }
}

private fun DownloadSort.labelRes(): Int = when (this) {
    DownloadSort.Recent -> R.string.downloads_sort_recent
    DownloadSort.Oldest -> R.string.downloads_sort_oldest
    DownloadSort.Title -> R.string.downloads_sort_title
    DownloadSort.Uploader -> R.string.downloads_sort_uploader
}
