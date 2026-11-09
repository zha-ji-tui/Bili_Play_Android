package io.github.zhajitui.biliplay.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zhajitui.biliplay.data.local.SearchHistoryStore
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.model.UploaderItem
import io.github.zhajitui.biliplay.data.repository.ContentRepository
import io.github.zhajitui.biliplay.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchCategory { Videos, Uploaders }

data class SearchUiState(
    val query: String = "",
    val category: SearchCategory = SearchCategory.Videos,
    val videos: List<Track> = emptyList(),
    val uploaders: List<UploaderItem> = emptyList(),
    val history: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(
    private val repository: ContentRepository,
    private val historyStore: SearchHistoryStore,
    private val accountId: Long,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SearchUiState(history = historyStore.load(accountId)),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var page = 0
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun selectCategory(category: SearchCategory) {
        val state = _uiState.value
        if (state.category == category) return
        val shouldSearch = state.hasSearched && state.query.isNotBlank()
        _uiState.value = state.copy(
            category = category,
            videos = emptyList(),
            uploaders = emptyList(),
            hasMore = false,
            hasSearched = false,
            error = null,
        )
        if (shouldSearch) search()
    }

    fun search() {
        val state = _uiState.value
        val query = state.query.trim()
        if (query.isEmpty()) return
        val category = state.category
        val history = historyStore.add(accountId, query)
        searchJob?.cancel()
        loadMoreJob?.cancel()
        page = 0
        _uiState.update {
            it.copy(
                videos = emptyList(),
                uploaders = emptyList(),
                history = history,
                isLoading = true,
                isLoadingMore = false,
                hasMore = false,
                hasSearched = true,
                error = null,
            )
        }
        searchJob = viewModelScope.launch {
            try {
                page = 1
                when (category) {
                    SearchCategory.Videos -> {
                        val result = repository.searchVideos(query, 1)
                        _uiState.update {
                            it.copy(videos = result.items, hasMore = result.hasMore, isLoading = false)
                        }
                    }

                    SearchCategory.Uploaders -> {
                        val result = repository.searchUploaders(query, 1)
                        _uiState.update {
                            it.copy(uploaders = result.items, hasMore = result.hasMore, isLoading = false)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, error = error.userMessage()) }
            }
        }
    }

    fun retry() = search()

    fun selectHistory(keyword: String) {
        _uiState.update { it.copy(query = keyword) }
        search()
    }

    fun removeHistory(keyword: String) {
        _uiState.update { it.copy(history = historyStore.remove(accountId, keyword)) }
    }

    fun clearHistory() {
        historyStore.clear(accountId)
        _uiState.update { it.copy(history = emptyList()) }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore || state.query.isBlank()) return
        val category = state.category
        val nextPage = page + 1
        _uiState.update { it.copy(isLoadingMore = true) }
        loadMoreJob = viewModelScope.launch {
            try {
                when (category) {
                    SearchCategory.Videos -> {
                        val result = repository.searchVideos(state.query.trim(), nextPage)
                        if (_uiState.value.category == category) {
                            page = nextPage
                            _uiState.update {
                                it.copy(
                                    videos = (it.videos + result.items).distinctBy { track -> track.bvid },
                                    hasMore = result.hasMore,
                                    isLoadingMore = false,
                                )
                            }
                        }
                    }

                    SearchCategory.Uploaders -> {
                        val result = repository.searchUploaders(state.query.trim(), nextPage)
                        if (_uiState.value.category == category) {
                            page = nextPage
                            _uiState.update {
                                it.copy(
                                    uploaders = (it.uploaders + result.items).distinctBy { uploader -> uploader.id },
                                    hasMore = result.hasMore,
                                    isLoadingMore = false,
                                )
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (_uiState.value.category == category) {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    companion object {
        fun factory(
            repository: ContentRepository,
            historyStore: SearchHistoryStore,
            accountId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(repository, historyStore, accountId) }
        }
    }
}
