package fr.spotifeur.presentation.components

import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import fr.spotifeur.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import fr.spotifeur.data.model.Song
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import fr.spotifeur.presentation.viewmodel.StablePlayerState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@Composable
internal fun UnifiedPlayerQueueLayer(
    shouldRenderLayer: Boolean,
    keepQueueSheetWarm: Boolean,
    albumColorScheme: ColorScheme,
    queueScrimAlpha: Float,
    showQueueSheet: Boolean,
    isQueueCollapsing: Boolean,
    queueHiddenOffsetPx: Float,
    queueSheetOffset: Animatable<Float, AnimationVector1D>,
    queueSheetHeightPx: Float,
    onQueueSheetHeightPxChange: (Float) -> Unit,
    configurationResetKey: Any,
    playerViewModel: PlayerViewModel,
    currentPlaybackQueue: ImmutableList<Song>,
    currentQueueSourceName: String,
    currentMediaItemIndex: Int,
    infrequentPlayerState: StablePlayerState,
    activeTimerValueDisplay: State<String?>,
    activeTimerDurationMinutes: State<Int?>,
    isEndOfTrackTimerActive: State<Boolean>,
    onDismissQueue: () -> Unit,
    onSongInfoClick: (Song) -> Unit,
    onPlaySong: (Song, Int) -> Unit,
    onRemoveSong: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onSetPredefinedTimer: (Int) -> Unit,
    onSetEndOfTrackTimer: (Boolean) -> Unit,
    onOpenCustomTimePicker: () -> Unit,
    onCancelTimer: () -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit,
    queuePredictiveBackProgress: Animatable<Float, AnimationVector1D>,
    queuePredictiveBackSwipeEdge: State<Int?>
) {
    if (!shouldRenderLayer) return

    LaunchedEffect(configurationResetKey) {
        onQueueSheetHeightPxChange(0f)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (queueScrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(0f)
                    .graphicsLayer { alpha = queueScrimAlpha }
                    .background(MaterialTheme.colorScheme.scrim)
            )
        }

        val shouldRenderQueueSheet = remember(showQueueSheet, keepQueueSheetWarm, queueSheetHeightPx) {
            showQueueSheet || keepQueueSheetWarm || queueSheetHeightPx == 0f
        }

        if (shouldRenderQueueSheet) {
            MaterialTheme(
                colorScheme = albumColorScheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes
            ) {
                QueueBottomSheet(
                    viewModel = playerViewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            val offsetVal = queueSheetOffset.value.roundToInt()
                            IntOffset(0, if (offsetVal < 0) 0 else offsetVal)
                        }
                        .graphicsLayer {
                            alpha = if (showQueueSheet || isQueueCollapsing) 1f else 0f
                        }
                        .onGloballyPositioned { coordinates ->
                            val measuredHeight = coordinates.size.height.toFloat()
                            if (queueSheetHeightPx != measuredHeight) {
                                onQueueSheetHeightPxChange(measuredHeight)
                            }
                        },
                    queue = currentPlaybackQueue,
                    currentQueueSourceName = currentQueueSourceName,
                    currentSongId = infrequentPlayerState.currentSong?.id,
                    currentMediaItemIndex = currentMediaItemIndex,
                    isVisible = showQueueSheet,
                    isPlaying = infrequentPlayerState.isPlaying,
                    onDismiss = onDismissQueue,
                    onSongInfoClick = onSongInfoClick,
                    onPlaySong = onPlaySong,
                    onRemoveSong = onRemoveSong,
                    onReorder = onReorder,
                    repeatMode = infrequentPlayerState.repeatMode,
                    isShuffleOn = infrequentPlayerState.isShuffleEnabled,
                    onToggleRepeat = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    onClearQueue = onClearQueue,
                    activeTimerValueDisplay = activeTimerValueDisplay,
                    activeTimerDurationMinutes = activeTimerDurationMinutes,
                        isEndOfTrackTimerActive = isEndOfTrackTimerActive,
                    onSetPredefinedTimer = onSetPredefinedTimer,
                    onSetEndOfTrackTimer = onSetEndOfTrackTimer,
                    onOpenCustomTimePicker = onOpenCustomTimePicker,
                    onCancelTimer = onCancelTimer,
                    onQueueDragStart = onQueueDragStart,
                    onQueueDrag = onQueueDrag,
                    onQueueRelease = onQueueRelease,
                    predictiveBackProgress = queuePredictiveBackProgress,
                    predictiveBackSwipeEdge = queuePredictiveBackSwipeEdge,
                    queueSheetOffset = queueSheetOffset
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun UnifiedPlayerSongInfoLayer(
    selectedSongForInfo: Song?,
    albumColorScheme: ColorScheme,
    playerViewModel: PlayerViewModel,
    currentPlaybackQueueProvider: () -> ImmutableList<Song>,
    currentQueueSourceNameProvider: () -> String,
    onDismissSongInfo: () -> Unit,
    onNavigateToAlbum: (Song) -> Unit,
    onNavigateToArtist: (Song) -> Unit,
    onStartRadio: (Song) -> Unit,
) {
    selectedSongForInfo?.let { staticSong ->
        val favorites by playerViewModel.model.favorites.collectAsStateWithLifecycle()
        val liveSong = staticSong.copy(isFavorite = staticSong.id in favorites)
        val playing by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
        MaterialTheme(
            colorScheme = albumColorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            SongInfoBottomSheet(
                song = liveSong,
                isFavorite = liveSong.isFavorite,
                playerViewModel = playerViewModel,
                onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(liveSong) },
                onDismiss = onDismissSongInfo,
                onPlaySong = {
                    playerViewModel.showAndPlaySong(
                        song = liveSong,
                        contextSongs = currentPlaybackQueueProvider(),
                        queueName = currentQueueSourceNameProvider()
                    )
                },
                onAddToQueue = { playerViewModel.addSongToQueue(liveSong) },
                onAddNextToQueue = { playerViewModel.addSongNextToQueue(liveSong) },
                onNavigateToAlbum = { onNavigateToAlbum(liveSong) },
                onNavigateToArtist = { onNavigateToArtist(liveSong) },
                onStartRadio = { onStartRadio(liveSong) },
                // The playing track (the full player's ⋮ button) is not removed from the queue from here.
                removeFromListTrigger = if (playing.currentSong?.id == liveSong.id) null else ({
                    playerViewModel.removeSongFromQueue(liveSong.id)
                    onDismissSongInfo()
                })
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun UnifiedPlayerQueueAndSongInfoHost(
    shouldRenderHost: Boolean,
    keepQueueSheetWarm: Boolean,
    isQueueTelemetryActive: Boolean,
    albumColorScheme: ColorScheme,
    queueScrimAlpha: Float,
    showQueueSheet: Boolean,
    isQueueCollapsing: Boolean,
    queueHiddenOffsetPx: Float,
    queueSheetOffset: Animatable<Float, AnimationVector1D>,
    queueSheetHeightPx: Float,
    onQueueSheetHeightPxChange: (Float) -> Unit,
    configurationResetKey: Any,
    currentQueueSourceName: String,
    infrequentPlayerState: StablePlayerState,
    playerViewModel: PlayerViewModel,
    selectedSongForInfo: Song?,
    onSelectedSongForInfoChange: (Song?) -> Unit,
    onAnimateQueueSheet: (Boolean) -> Unit,
    onBeginQueueDrag: () -> Unit,
    onDragQueueBy: (Float) -> Unit,
    onEndQueueDrag: (Float, Float) -> Unit,
    onNavigateToAlbum: (Song) -> Unit,
    onNavigateToArtist: (Song) -> Unit,
    onStartRadio: (Song) -> Unit,
    queuePredictiveBackProgress: Animatable<Float, AnimationVector1D>,
    queuePredictiveBackSwipeEdge: State<Int?>
) {
    if (!shouldRenderHost) return

    // Scoped queue collection: only the queue sheet / song-info host observes
    // the queue. The outer player sheet no longer recomposes on queue changes.
    val currentPlaybackQueue by playerViewModel.queueFlow.collectAsStateWithLifecycle()
    val latestPlaybackQueue = rememberUpdatedState(currentPlaybackQueue)
    val latestQueueSourceName = rememberUpdatedState(currentQueueSourceName)
    val inactiveTimerValueDisplayState = rememberUpdatedState<String?>(null)
    val inactiveTimerDurationMinutesState = rememberUpdatedState<Int?>(null)
    val inactiveEndOfTrackTimerActiveState = rememberUpdatedState(false)
    val activeTimerValueDisplay: State<String?> =
        if (isQueueTelemetryActive) {
            playerViewModel.activeTimerValueDisplay.collectAsStateWithLifecycle()
        } else {
            inactiveTimerValueDisplayState
        }
    val activeTimerDurationMinutes: State<Int?> =
        if (isQueueTelemetryActive) {
            playerViewModel.activeTimerDurationMinutes.collectAsStateWithLifecycle()
        } else {
            inactiveTimerDurationMinutesState
        }
    val isEndOfTrackTimerActive: State<Boolean> =
        if (isQueueTelemetryActive) {
            playerViewModel.isEndOfTrackTimerActive.collectAsStateWithLifecycle()
        } else {
            inactiveEndOfTrackTimerActiveState
        }

    CompositionLocalProvider(
        LocalMaterialTheme provides albumColorScheme
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val onDismissQueueRequest = remember(onAnimateQueueSheet) { { onAnimateQueueSheet(false) } }
            val onQueueSongInfoClick = remember(onSelectedSongForInfoChange) {
                { song: Song -> onSelectedSongForInfoChange(song) }
            }
            val onPlayQueueSong = remember(playerViewModel) {
                { song: Song, index: Int ->
                    playerViewModel.showAndPlaySong(
                        song = song,
                        contextSongs = latestPlaybackQueue.value,
                        queueName = latestQueueSourceName.value,
                        indexInQueue = index
                    )
                }
            }
            val onRemoveQueueSong = remember(playerViewModel) {
                { id: String -> playerViewModel.removeSongFromQueue(id) }
            }
            val onReorderQueue = remember(playerViewModel) {
                { from: Int, to: Int -> playerViewModel.reorderQueueItem(from, to) }
            }
            val onToggleRepeat = remember(playerViewModel) { { playerViewModel.cycleRepeatMode() } }
            val onToggleShuffle = remember(playerViewModel) { { playerViewModel.toggleShuffle() } }
            val onClearQueue = remember(playerViewModel) { { playerViewModel.clearQueueExceptCurrent() } }
            val onSetPredefinedTimer = remember(playerViewModel) {
                { minutes: Int -> playerViewModel.setSleepTimer(minutes) }
            }
            val onSetEndOfTrackTimer = remember(playerViewModel) {
                { enable: Boolean -> playerViewModel.setEndOfTrackTimer(enable) }
            }
            val onOpenCustomTimePicker: () -> Unit = remember {
                { Log.d("TimerOptions", "OpenCustomTimePicker clicked") }
            }
            val onCancelTimer = remember(playerViewModel) { { playerViewModel.cancelSleepTimer() } }
            val onQueueStartDrag = remember(onBeginQueueDrag) { { onBeginQueueDrag() } }
            val onQueueDrag = remember(onDragQueueBy) { { drag: Float -> onDragQueueBy(drag) } }
            val onQueueRelease = remember(onEndQueueDrag) {
                { drag: Float, vel: Float -> onEndQueueDrag(drag, vel) }
            }
            val playbackQueueProvider = remember {
                { latestPlaybackQueue.value }
            }
            val queueSourceNameProvider = remember {
                { latestQueueSourceName.value }
            }

            UnifiedPlayerQueueLayer(
                shouldRenderLayer = true,
                keepQueueSheetWarm = keepQueueSheetWarm,
                albumColorScheme = albumColorScheme,
                queueScrimAlpha = queueScrimAlpha,
                showQueueSheet = showQueueSheet,
                isQueueCollapsing = isQueueCollapsing,
                queueHiddenOffsetPx = queueHiddenOffsetPx,
                queueSheetOffset = queueSheetOffset,
                queueSheetHeightPx = queueSheetHeightPx,
                onQueueSheetHeightPxChange = onQueueSheetHeightPxChange,
                configurationResetKey = configurationResetKey,
                playerViewModel = playerViewModel,
                currentPlaybackQueue = currentPlaybackQueue,
                currentQueueSourceName = currentQueueSourceName,
                currentMediaItemIndex = infrequentPlayerState.currentMediaItemIndex,
                infrequentPlayerState = infrequentPlayerState,
                activeTimerValueDisplay = activeTimerValueDisplay,
                activeTimerDurationMinutes = activeTimerDurationMinutes,
                isEndOfTrackTimerActive = isEndOfTrackTimerActive,
                onDismissQueue = onDismissQueueRequest,
                onSongInfoClick = onQueueSongInfoClick,
                onPlaySong = onPlayQueueSong,
                onRemoveSong = onRemoveQueueSong,
                onReorder = onReorderQueue,
                onToggleRepeat = onToggleRepeat,
                onToggleShuffle = onToggleShuffle,
                onClearQueue = onClearQueue,
                onSetPredefinedTimer = onSetPredefinedTimer,
                onSetEndOfTrackTimer = onSetEndOfTrackTimer,
                onOpenCustomTimePicker = onOpenCustomTimePicker,
                onCancelTimer = onCancelTimer,
                onQueueDragStart = onQueueStartDrag,
                onQueueDrag = onQueueDrag,
                onQueueRelease = onQueueRelease,
                queuePredictiveBackProgress = queuePredictiveBackProgress,
                queuePredictiveBackSwipeEdge = queuePredictiveBackSwipeEdge
            )

            UnifiedPlayerSongInfoLayer(
                selectedSongForInfo = selectedSongForInfo,
                albumColorScheme = albumColorScheme,
                playerViewModel = playerViewModel,
                currentPlaybackQueueProvider = playbackQueueProvider,
                currentQueueSourceNameProvider = queueSourceNameProvider,
                onDismissSongInfo = { onSelectedSongForInfoChange(null) },
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onStartRadio = onStartRadio
            )
        }
    }
}

