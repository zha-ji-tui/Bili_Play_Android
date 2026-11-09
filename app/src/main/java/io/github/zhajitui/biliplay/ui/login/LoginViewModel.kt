package io.github.zhajitui.biliplay.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zhajitui.biliplay.data.api.BilibiliException
import io.github.zhajitui.biliplay.data.repository.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

data class LoginUiState(
    val qrUrl: String? = null,
    val status: String = "正在获取登录二维码…",
    val isLoading: Boolean = true,
)

class LoginViewModel(
    private val repository: AccountRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var loginJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loginJob?.cancel()
        _uiState.value = LoginUiState()
        loginJob = viewModelScope.launch {
            try {
                val code = repository.beginLogin()
                _uiState.value = LoginUiState(
                    qrUrl = code.url,
                    status = "打开哔哩哔哩 App，扫码登录",
                    isLoading = false,
                )
                withTimeout(LOGIN_TIMEOUT_MS) {
                    while (isActive) {
                        delay(POLL_INTERVAL_MS)
                        when (repository.pollLogin(code.key)) {
                            0 -> {
                                repository.completeLogin()
                                return@withTimeout
                            }
                            86090 -> _uiState.update {
                                it.copy(status = "已扫码，请在手机上确认登录")
                            }
                            86038 -> {
                                _uiState.update {
                                    it.copy(status = "二维码已过期，请刷新后重试")
                                }
                                return@withTimeout
                            }
                            86101 -> Unit
                            else -> {
                                _uiState.update {
                                    it.copy(status = "登录未完成，请刷新二维码重试")
                                }
                                return@withTimeout
                            }
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                _uiState.update {
                    it.copy(status = "二维码已过期，请刷新后重试")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: BilibiliException) {
                _uiState.update {
                    it.copy(
                        status = error.message ?: "登录未完成，请刷新二维码重试",
                        isLoading = false,
                    )
                }
            } catch (error: IOException) {
                _uiState.update {
                    it.copy(
                        status = "网络连接失败，请检查网络后重试",
                        isLoading = false,
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        status = "暂时无法登录，请重试",
                        isLoading = false,
                    )
                }
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val LOGIN_TIMEOUT_MS = 3 * 60 * 1000L

        fun factory(repository: AccountRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { LoginViewModel(repository) }
        }
    }
}
