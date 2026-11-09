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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zhajitui.biliplay.R
import io.github.zhajitui.biliplay.data.download.DownloadState
import io.github.zhajitui.biliplay.data.local.SearchHistoryStore
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.repository.ContentRepository
import io.github.zhajitui.biliplay.ui.components.AutoLoadMore
import io.github.zhajitui.biliplay.ui.components.EmptyState
import io.github.zhajitui.biliplay.ui.components.ErrorState
import io.github.zhajitui.biliplay.ui.components.LoadingFooter
import io.github.zhajitui.biliplay.ui.components.TrackCard
import io.github.zhajitui.biliplay.ui.components.UploaderCard
import io.github.zhajitui.biliplay.ui.search.SearchCategory
import io.github.zhajitui.biliplay.ui.search.SearchViewModel

@Composable
fun SearchScreen(
    repository: ContentRepository,
    onPlay: (List<Track>, Int) -> Unit,
    downloadStates: Map<String, DownloadState>,
    onDownload: (Track) -> Unit,
    historyStore: SearchHistoryStore,
    accountId: Long,
) {
    val viewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.factory(repository, historyStore, accountId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        viewModel.search()
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.search()
                },
                enabled = state.query.isNotBlank(),
            ) {
                Text(stringResource(R.string.search_action))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.category == SearchCategory.Videos,
                onClick = { viewModel.selectCategory(SearchCategory.Videos) },
                label = { Text(stringResource(R.string.search_category_videos)) },
            )
            FilterChip(
                selected = state.category == SearchCategory.Uploaders,
                onClick = { viewModel.selectCategory(SearchCategory.Uploaders) },
                label = { Text(stringResource(R.string.search_category_uploaders)) },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                (!state.hasSearched || state.query.isBlank()) && state.history.isNotEmpty() ->
                    SearchHistoryList(
                        history = state.history,
                        onSelect = viewModel::selectHistory,
                        onDelete = viewModel::removeHistory,
                        onClear = viewModel::clearHistory,
                    )

                state.error != null -> ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::retry,
                    modifier = Modifier.align(Alignment.Center),
                )

                !state.hasSearched -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.search_title),
                    message = stringResource(R.string.search_empty_hint),
                    modifier = Modifier.fillMaxSize(),
                )

                (state.category == SearchCategory.Videos && state.videos.isEmpty()) ||
                    (state.category == SearchCategory.Uploaders && state.uploaders.isEmpty()) -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.search_title),
                    message = stringResource(R.string.search_no_results),
                    modifier = Modifier.fillMaxSize(),
                )

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.category == SearchCategory.Videos) {
                            itemsIndexed(
                                state.videos,
                                key = { _, track -> track.bvid },
                            ) { index, track ->
                                TrackCard(
                                    track = track,
                                    onClick = { onPlay(state.videos, index) },
                                    downloadState = downloadStates[track.bvid],
                                    onDownload = { onDownload(track) },
                                )
                            }
                        } else {
                            items(state.uploaders, key = { it.id }) { uploader ->
                                UploaderCard(uploader)
                            }
                        }
                        if (state.isLoadingMore) {
                            item { LoadingFooter(visible = true) }
                        }
                    }
                    AutoLoadMore(listState, viewModel::loadMore)
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryList(
    history: List<String>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.search_history_clear))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(history, key = { it }) { keyword ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onSelect(keyword) }
                        .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = keyword,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onDelete(keyword) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.search_history_delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
