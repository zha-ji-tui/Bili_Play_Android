package io.github.zhajitui.biliplay.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.zhajitui.biliplay.BiliPlayApplication
import io.github.zhajitui.biliplay.R
import io.github.zhajitui.biliplay.data.download.AudioDownloadManager
import io.github.zhajitui.biliplay.data.local.SearchHistoryStore
import io.github.zhajitui.biliplay.data.model.Account
import io.github.zhajitui.biliplay.data.repository.ContentRepository
import io.github.zhajitui.biliplay.data.repository.SessionState
import io.github.zhajitui.biliplay.playback.PlaybackConnection
import io.github.zhajitui.biliplay.ui.login.LoginScreen
import io.github.zhajitui.biliplay.ui.player.MiniPlayer
import io.github.zhajitui.biliplay.ui.player.PlayerViewModel
import io.github.zhajitui.biliplay.ui.player.PreparingBar
import io.github.zhajitui.biliplay.ui.screens.AboutScreen
import io.github.zhajitui.biliplay.ui.screens.DownloadsScreen
import io.github.zhajitui.biliplay.ui.screens.HomeScreen
import io.github.zhajitui.biliplay.ui.screens.PlayerScreen
import io.github.zhajitui.biliplay.ui.screens.ProfileScreen
import io.github.zhajitui.biliplay.ui.screens.SearchScreen
import kotlinx.coroutines.flow.collect

private data class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("home", R.string.tab_home, Icons.Filled.Home),
    TopLevelDestination("search", R.string.tab_search, Icons.Filled.Search),
    TopLevelDestination("downloads", R.string.tab_downloads, Icons.Filled.Download),
    TopLevelDestination("profile", R.string.tab_profile, Icons.Filled.Person),
)

private const val NAV_FADE_MS = 200

@Composable
fun BiliPlayApp() {
    val context = LocalContext.current
    val container = (context.applicationContext as BiliPlayApplication).container
    val repository = container.accountRepository
    val sessionState by repository.state.collectAsStateWithLifecycle()

    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.LoggedOut) {
            container.playbackConnection.stop()
        }
    }

    when (val state = sessionState) {
        SessionState.Loading -> SplashScreen()
        SessionState.LoggedOut -> LoginScreen(repository = repository)
        is SessionState.LoggedIn -> MainNavigation(
            account = state.account,
            contentRepository = container.contentRepository,
            playbackConnection = container.playbackConnection,
            downloadManager = container.audioDownloadManager,
            searchHistoryStore = container.searchHistoryStore,
            onLogout = repository::logout,
        )
    }
}

@Composable
private fun MainNavigation(
    account: Account,
    contentRepository: ContentRepository,
    playbackConnection: PlaybackConnection,
    downloadManager: AudioDownloadManager,
    searchHistoryStore: SearchHistoryStore,
    onLogout: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topLevelDestinations.any { it.route == currentRoute }

    val playerViewModel: PlayerViewModel = viewModel(
        key = "player-${account.id}",
        factory = PlayerViewModel.factory(
            repository = contentRepository,
            account = account,
            connection = playbackConnection,
            localUriProvider = downloadManager::localUri,
        ),
    )
    val playerState by playerViewModel.playback.collectAsStateWithLifecycle()
    val preparingTrack by playerViewModel.preparingTrack.collectAsStateWithLifecycle()
    val downloadStates by downloadManager.states.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(playerViewModel) {
        playerViewModel.errors.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(downloadManager) {
        downloadManager.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (currentRoute != "player" && preparingTrack != null && !playerState.hasMedia) {
                    PreparingBar(
                        track = preparingTrack,
                        modifier = if (showBottomBar) Modifier else Modifier.navigationBarsPadding(),
                    )
                }
                if (currentRoute != "player" && playerState.hasMedia) {
                    MiniPlayer(
                        state = playerState,
                        onOpen = {
                            navController.navigate("player") { launchSingleTop = true }
                        },
                        onToggle = playerViewModel::togglePlayPause,
                        onClose = playerViewModel::stop,
                        onCycleMode = playerViewModel::cycleRepeatMode,
                        modifier = if (showBottomBar) Modifier else Modifier.navigationBarsPadding(),
                    )
                }
                if (showBottomBar) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        topLevelDestinations.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(destination.icon, contentDescription = null)
                                },
                                label = {
                                    Text(stringResource(destination.labelRes))
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(animationSpec = tween(NAV_FADE_MS)) },
            exitTransition = { fadeOut(animationSpec = tween(NAV_FADE_MS)) },
            popEnterTransition = { fadeIn(animationSpec = tween(NAV_FADE_MS)) },
            popExitTransition = { fadeOut(animationSpec = tween(NAV_FADE_MS)) },
        ) {
            composable("home") {
                HomeScreen(
                    repository = contentRepository,
                    onPlay = playerViewModel::play,
                    downloadStates = downloadStates,
                    onDownload = downloadManager::download,
                )
            }
            composable("search") {
                SearchScreen(
                    repository = contentRepository,
                    onPlay = playerViewModel::play,
                    downloadStates = downloadStates,
                    onDownload = downloadManager::download,
                    historyStore = searchHistoryStore,
                    accountId = account.id,
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    downloadManager = downloadManager,
                    onPlay = playerViewModel::play,
                )
            }
            composable("profile") {
                ProfileScreen(
                    account = account,
                    onOpenAbout = { navController.navigate("about") },
                    onLogout = onLogout,
                )
            }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable("player") {
                val progress by playerViewModel.progress.collectAsStateWithLifecycle()
                PlayerScreen(
                    state = playerState,
                    progress = progress,
                    onBack = { navController.popBackStack() },
                    onToggle = playerViewModel::togglePlayPause,
                    onSkipPrevious = playerViewModel::skipToPrevious,
                    onSkipNext = playerViewModel::skipToNext,
                    onCycleMode = playerViewModel::cycleRepeatMode,
                    onSeek = playerViewModel::seekTo,
                    onStop = {
                        playerViewModel.stop()
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}
