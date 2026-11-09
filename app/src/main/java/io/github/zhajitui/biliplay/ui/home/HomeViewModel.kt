package io.github.zhajitui.biliplay.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zhajitui.biliplay.data.model.Track
import io.github.zhajitui.biliplay.data.repository.ContentRepository
import io.github.zhajitui.biliplay.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(
    private val repository: ContentRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var page = 0
    private var job: Job? = null
    private var loadMoreJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        loadMoreJob?.cancel()
        page = 0
        _uiState.update { it.copy(isLoading = true, isLoadingMore = false, error = null) }
        job = viewModelScope.launch {
            try {
                val result = repository.homeFeed(1)
                page = 1
                _uiState.value = HomeUiState(
                    tracks = result.items,
                    isLoading = false,
                    isLoadingMore = false,
                    hasMore = result.hasMore,
                    error = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, error = error.userMessage()) }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        val nextPage = page + 1
        _uiState.update { it.copy(isLoadingMore = true) }
        loadMoreJob = viewModelScope.launch {
            try {
                val result = repository.homeFeed(nextPage)
                page = nextPage
                _uiState.update {
                    it.copy(
                        tracks = (it.tracks + result.items).distinctBy { track -> track.bvid },
                        hasMore = result.hasMore,
                        isLoadingMore = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    companion object {
        fun factory(repository: ContentRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}
