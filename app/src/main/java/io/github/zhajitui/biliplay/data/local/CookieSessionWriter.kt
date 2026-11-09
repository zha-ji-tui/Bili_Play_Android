package io.github.zhajitui.biliplay.data.local

import io.github.zhajitui.biliplay.data.model.SavedCookie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CookieSessionWriter(
    private val store: SecureSessionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null

    fun schedule(cookies: List<SavedCookie>) {
        synchronized(lock) {
            job?.cancel()
            job = scope.launch {
                delay(1_500)
                store.save(cookies)
            }
        }
    }
}
