package io.github.zhajitui.biliplay.data.repository

import io.github.zhajitui.biliplay.data.api.BilibiliApi
import io.github.zhajitui.biliplay.data.api.BilibiliException
import io.github.zhajitui.biliplay.data.local.SecureSessionStore
import io.github.zhajitui.biliplay.data.model.Account
import io.github.zhajitui.biliplay.data.model.LoginCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val account: Account) : SessionState
}

class AccountRepository(
    private val api: BilibiliApi,
    private val store: SecureSessionStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun start() {
        scope.launch { restore() }
    }

    suspend fun beginLogin(): LoginCode {
        api.warmUp()
        return api.createLoginCode()
    }

    suspend fun pollLogin(key: String): Int = api.pollLogin(key)

    suspend fun completeLogin(): Account = withContext(Dispatchers.IO) {
        val account = api.getAccount()
        store.saveAccount(account)
        store.save(api.exportCookies())
        _state.value = SessionState.LoggedIn(account)
        account
    }

    fun logout() {
        scope.launch {
            api.clearSession()
            withContext(Dispatchers.IO) { store.clear() }
            _state.value = SessionState.LoggedOut
        }
    }

    private suspend fun restore() {
        val cookies = withContext(Dispatchers.IO) { store.load() }
        if (cookies.isEmpty()) {
            _state.value = SessionState.LoggedOut
            return
        }
        api.importCookies(cookies)
        val cached = withContext(Dispatchers.IO) { store.loadAccount() }
        if (cached != null) _state.value = SessionState.LoggedIn(cached)
        runCatching { api.getAccount() }
            .onSuccess { account ->
                withContext(Dispatchers.IO) {
                    store.saveAccount(account)
                    store.save(api.exportCookies())
                }
                _state.value = SessionState.LoggedIn(account)
            }
            .onFailure { error ->
                if (error is BilibiliException && error.requiresLogin) {
                    api.clearSession()
                    withContext(Dispatchers.IO) { store.clear() }
                    _state.value = SessionState.LoggedOut
                } else if (cached == null) {
                    _state.value = SessionState.LoggedOut
                }
            }
    }
}
