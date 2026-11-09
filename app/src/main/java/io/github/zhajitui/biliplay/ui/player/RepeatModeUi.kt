package io.github.zhajitui.biliplay.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.zhajitui.biliplay.R
import io.github.zhajitui.biliplay.playback.RepeatMode

internal fun RepeatMode.icon(): ImageVector = when (this) {
    RepeatMode.ListLoop -> Icons.Filled.Repeat
    RepeatMode.SingleLoop -> Icons.Filled.RepeatOne
    RepeatMode.Sequential -> Icons.AutoMirrored.Filled.ArrowForward
    RepeatMode.Shuffle -> Icons.Filled.Shuffle
}

@Composable
internal fun RepeatMode.label(): String = stringResource(
    when (this) {
        RepeatMode.ListLoop -> R.string.mode_list_loop
        RepeatMode.SingleLoop -> R.string.mode_single_loop
        RepeatMode.Sequential -> R.string.mode_sequential
        RepeatMode.Shuffle -> R.string.mode_shuffle
    },
)
