package io.github.zhajitui.biliplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zhajitui.biliplay.R
import io.github.zhajitui.biliplay.data.download.DownloadState
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.repository.ContentRepository
import io.github.zhajitui.biliplay.ui.components.AutoLoadMore
import io.github.zhajitui.biliplay.ui.components.EmptyState
import io.github.zhajitui.biliplay.ui.components.ErrorState
import io.github.zhajitui.biliplay.ui.components.LoadingFooter
import io.github.zhajitui.biliplay.ui.components.TrackCard
import io.github.zhajitui.biliplay.ui.home.HomeViewModel

@Composable
fun HomeScreen(
    repository: ContentRepository,
    onPlay: (List<Track>, Int) -> Unit,
    downloadStates: Map<String, DownloadState>,
    onDownload: (Track) -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BrandHeader() }
        item {
            Text(
                text = stringResource(R.string.home_feed_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        when {
            state.isLoading -> item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.tracks.isEmpty() && state.error != null -> item {
                ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::refresh,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }

            state.tracks.isEmpty() -> item {
                EmptyState(
                    icon = Icons.Filled.PlayArrow,
                    title = stringResource(R.string.home_feed_title),
                    message = stringResource(R.string.home_feed_empty),
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }

            else -> {
                itemsIndexed(state.tracks, key = { _, track -> track.bvid }) { index, track ->
                    TrackCard(
                        track = track,
                        onClick = { onPlay(state.tracks, index) },
                        downloadState = downloadStates[track.bvid],
                        onDownload = { onDownload(track) },
                    )
                }
                if (state.hasMore || state.isLoadingMore) {
                    item { LoadingFooter(visible = state.isLoadingMore) }
                }
            }
        }
    }
    AutoLoadMore(listState, viewModel::loadMore)
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "B",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
