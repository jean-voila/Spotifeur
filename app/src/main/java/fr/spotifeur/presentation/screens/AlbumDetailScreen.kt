package fr.spotifeur.presentation.screens

import fr.spotifeur.data.source
import androidx.compose.material.icons.rounded.Share
import fr.spotifeur.presentation.utils.shareMusic
import fr.spotifeur.presentation.utils.spotifyShareUrl
import androidx.compose.foundation.layout.FlowRow
import fr.spotifeur.ui.theme.SpotifeurStatusBarStyle
import fr.spotifeur.utils.formatSongCount
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImagePainter
import coil.size.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.FilledTonalIconButton
import androidx.navigation.NavHostController
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.model.toSong
import fr.spotifeur.presentation.components.SmartImage
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.components.subcomps.EnhancedSongListItem
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.ui.theme.LocalSpotifeurDarkTheme
import fr.spotifeur.utils.shapes.RoundedStarShape
import androidx.compose.foundation.lazy.itemsIndexed
import fr.spotifeur.R
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.Album
import fr.spotifeur.presentation.viewmodel.PlayerViewModel


@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumDetailScreen(
    model: SpotifeurModel,
    navController: NavHostController,
    albumId: Long,
) {
    val ui = model.ui
    val playerViewModel = ui
    val item = remember(albumId) { ui.entity(albumId) }
    if (item == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val details by model.details.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stablePlayerState by ui.stablePlayerState.collectAsStateWithLifecycle()
    LaunchedEffect(item.id) { model.loadDetail(item) }
    val detail = details[item.id]
    val current = detail?.content?.item ?: item
    var selectedSong by remember { mutableStateOf<Song?>(null) }
    val songs = remember(detail?.content?.tracks, saved) {
        detail?.content?.tracks.orEmpty().map { it.toSong(it.id in saved) }
    }
    CollectionDetailLayout(current, detail?.content?.total ?: songs.size, ui, navController, rememberLazyListState()) {
        item(key = "album_actions") {
            // YouTube Music albums cannot be saved to Spotify or seed a Spotify radio.
            val spotifyAlbum = current.source == fr.spotifeur.data.MusicSource.Spotify
            DetailActionsRow(current, spotifyAlbum, current.id in saved, pins.any { it.id == current.id },
                { model.toggleFavorite(current) }, { ui.togglePin(current) },
                { ui.startRadio(current) { navController.openMusic(ui, it) } }, ui, showRadio = spotifyAlbum)
            fr.spotifeur.presentation.components.CollectionDownloadProgress(ui, current)
        }
        if (detail?.error != null) item { Text(detail.error, color = MaterialTheme.colorScheme.error) }
        if (songs.isEmpty() && detail?.loading != false) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        }
        itemsIndexed(songs, key = { index, song -> "album:$index:${song.id}" }) { _, song ->
            val isCurrent = stablePlayerState.currentSong?.id == song.id
            EnhancedSongListItem(song = song, isCurrentSong = isCurrent,
                isPlaying = isCurrent && stablePlayerState.isPlaying, showAlbumArt = false,
                onMoreOptionsClick = { selectedSong = it }, onLongPress = { selectedSong = song },
                onClick = { ui.playSongs(songs, song, current.title) })
        }
    }
    SongOptionsHost(ui, navController, selectedSong, songs, current.title) { selectedSong = null }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollapsingAlbumTopBar(
    album: Album,
    songsCount: Int,
    collapseFraction: Float,
    headerHeight: Dp,
    headerImageRequestSize: Size,
    onHeaderArtworkState: ((AsyncImagePainter.State) -> Unit)? = null,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val statusBarColor =
        if (LocalSpotifeurDarkTheme.current) Color.Black.copy(alpha = 0.6f) else Color.White.copy(
            alpha = 0.4f
        )

    // Animation Values
    val fabScale = 1f - collapseFraction
    val backgroundAlpha = collapseFraction
    val headerContentAlpha = 1f - (collapseFraction * 2).coerceAtMost(1f)
    val showExpandedArtwork = headerContentAlpha > 0.01f
    val headerOverlayBrush = remember(surfaceColor, headerContentAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                surfaceColor.copy(alpha = 0.30f * headerContentAlpha),
                surfaceColor.copy(alpha = 0.90f * headerContentAlpha),
                surfaceColor.copy(alpha = headerContentAlpha)
            )
        )
    }
    val statusBarBrush = remember(statusBarColor) {
        Brush.verticalGradient(
            colors = listOf(
                statusBarColor,
                Color.Transparent
            )
        )
    }
    val solidAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
    val expandedStatusBarFallback = remember(statusBarColor, surfaceColor) {
        statusBarColor.compositeOver(surfaceColor)
    }
    val fallbackStatusBarColor = remember(expandedStatusBarFallback, surfaceColor, solidAlpha) {
        lerpColor(expandedStatusBarFallback, surfaceColor, solidAlpha)
    }

    // Title animation
    val titleScale = lerp(1f, 0.75f, collapseFraction)
    val titlePaddingStart = lerp(24.dp, 58.dp, collapseFraction)
    val titleMaxLines = if (collapseFraction < 0.5f) 2 else 1
    val titleVerticalBias = lerp(1f, -1f, collapseFraction)
    val animatedTitleAlignment =
        BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias)
    val titleContainerHeight = lerp(88.dp, 56.dp, collapseFraction)
    val yOffsetCorrection = lerp((titleContainerHeight / 2) - 64.dp, 0.dp, collapseFraction)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clipToBounds()
    ) {
        SpotifeurStatusBarStyle(color = fallbackStatusBarColor)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(surfaceColor.copy(alpha = backgroundAlpha))
        ) {
            if (showExpandedArtwork) {
                SmartImage(
                    model = album.albumArtUriString,
                    contentDescription = stringResource(R.string.album_cover_for, album.title),
                    contentScale = ContentScale.Crop,
                    targetSize = headerImageRequestSize,
                    allowHardware = true,
                    crossfadeDurationMillis = 0,
                    alpha = headerContentAlpha,
                    onState = onHeaderArtworkState,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(headerOverlayBrush)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(statusBarBrush)
                    .align(Alignment.TopCenter)
            )

            // Top bar content (buttons, title, etc.)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                FilledIconButton(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 4.dp),
                    onClick = onBackPressed,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }

                Box(
                    modifier = Modifier
                        .align(animatedTitleAlignment)
                        .height(titleContainerHeight)
                        .fillMaxWidth()
                        .offset(y = yOffsetCorrection)
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = titlePaddingStart, end = 120.dp)
                            .graphicsLayer {
                                scaleX = titleScale
                                scaleY = titleScale
                            },
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 26.sp,
                                textGeometricTransform = TextGeometricTransform(scaleX = 1.2f),
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.album_detail_meta_line,
                                album.artist,
                                formatSongCount(songsCount)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                LargeExtendedFloatingActionButton(
                    onClick = onPlayClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = DetailFabShape,
                    // Shadows of concave outlines are re-tessellated by the renderer on every
                    // frame: with this 360-point star it saturates it and freezes the UI.
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                            alpha = fabScale
                        }
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.common_shuffle_play_album))
                }
            }
        }
    }
}

internal val DetailFabShape = RoundedStarShape(sides = 8, curve = 0.05, rotation = 0f)

/** Buttons of an album or artist page, laid out as chosen in Settings. */
@Composable
internal fun DetailActionsRow(
    item: Music,
    canSave: Boolean,
    isSaved: Boolean,
    isPinned: Boolean,
    onSave: () -> Unit,
    onPin: () -> Unit,
    onRadio: () -> Unit,
    ui: PlayerViewModel,
    showRadio: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = buildList {
        add(downloadAction(ui, item))
        if (canSave) add(ActionSpec(fr.spotifeur.data.preferences.DetailAction.SAVE,
            if (isSaved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            stringResource(if (isSaved) R.string.saved_remove else R.string.saved_add), onSave, selected = isSaved))
        add(ActionSpec(fr.spotifeur.data.preferences.DetailAction.PIN,
            if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
            stringResource(if (isPinned) R.string.pin_remove else R.string.pin_add_home), onPin, selected = isPinned))
        if (showRadio) add(ActionSpec(fr.spotifeur.data.preferences.DetailAction.RADIO, Icons.Rounded.Radio, stringResource(R.string.radio_related), onRadio))
        if (item.spotifyShareUrl() != null) add(ActionSpec(fr.spotifeur.data.preferences.DetailAction.SHARE, Icons.Rounded.Share,
            stringResource(R.string.share_item, item.title), { context.shareMusic(item) }))
    }
    DetailActionBar(fr.spotifeur.data.preferences.DetailPage.ALBUM, actions, ui)
}

