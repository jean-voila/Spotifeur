package fr.spotifeur.presentation.components

import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.presentation.utils.shareMusic
import fr.spotifeur.presentation.utils.spotifyShareUrl
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.TableChart
import fr.spotifeur.data.isLocalPlaylist
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import fr.spotifeur.data.model.toMusic
import fr.spotifeur.R
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.Album
import fr.spotifeur.data.model.Artist
import fr.spotifeur.data.model.Playlist
import fr.spotifeur.ui.theme.GoogleSansRounded
import fr.spotifeur.ui.theme.LocalSpotifeurDarkTheme
import fr.spotifeur.presentation.viewmodel.ColorSchemePair
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import fr.spotifeur.presentation.components.subcomps.AutoSizingTextToFill
import fr.spotifeur.presentation.components.subcomps.TightWrapText
import fr.spotifeur.utils.formatDuration
import fr.spotifeur.ui.theme.ExpTitleTypography
import fr.spotifeur.presentation.screens.TabAnimation


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongInfoBottomSheet(
    song: Song,
    isFavorite: Boolean,
    playerViewModel: PlayerViewModel,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onPlaySong: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddNextToQueue: () -> Unit,
    onNavigateToAlbum: () -> Unit,
    onNavigateToArtist: () -> Unit,
    onStartRadio: () -> Unit,
    removeFromListTrigger: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val infoShape = RoundedCornerShape(16.dp)
    val discovery by playerViewModel.model.discovery.collectAsStateWithLifecycle()
    // Likes and radios are Spotify features: they need a Spotify track and an account.
    val spotifyPages = song.source == fr.spotifeur.data.MusicSource.Spotify && discovery.spotifyEnabled
    // Albums and artists open on Spotify with an account, on YouTube Music otherwise (issue #8).
    val catalogPages = !song.isLocal
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OptionsSheetHeader(song.albumArtUriString, song.title, song.displayArtist)
            Row1Actions(
                isFavorite = isFavorite,
                onPlaySong = { onPlaySong(); onDismiss() },
                onToggleFavorite = if (spotifyPages) onToggleFavorite else null,
                onShareClick = if (song.isLocal) null else ({ context.shareMusic(song.toMusic()) }),
                playButtonShape = OptionsPlayButtonShape,
                evenCornerRadiusElems = 26.dp
            )
            Row2Actions(onAddToQueue = onAddToQueue, onAddNextToQueue = onAddNextToQueue)
            RadioAndPinRow(
                item = song.toMusic(),
                playerViewModel = playerViewModel,
                onStartRadio = if (spotifyPages) onStartRadio else null,
            )
            onRemoveFromPlaylist?.let { remove ->
                TextButton(onClick = { remove(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.playlist_remove_track))
                }
            }
            SongInfoSegmentedListItem(
                headline = stringResource(R.string.song_info_duration_label),
                supporting = formatDuration(song.duration),
                icon = Icons.Rounded.Schedule,
                iconDescription = stringResource(R.string.song_info_duration_label),
                shape = infoShape,
            )
            SongInfoSegmentedListItem(
                headline = stringResource(R.string.song_info_album_label),
                supporting = song.album.ifBlank { stringResource(R.string.open_album) },
                icon = Icons.Rounded.Album,
                iconDescription = stringResource(R.string.song_info_album_label),
                shape = infoShape,
                onClick = if (catalogPages) onNavigateToAlbum else null,
            )
            SongInfoSegmentedListItem(
                headline = stringResource(R.string.song_info_artist_label),
                supporting = song.displayArtist,
                icon = Icons.Rounded.Person,
                iconDescription = stringResource(R.string.song_info_artist_label),
                shape = infoShape,
                onClick = if (catalogPages) onNavigateToArtist else null,
            )
            if (removeFromListTrigger != null) {
                TextButton(onClick = removeFromListTrigger, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.remove_from_queue))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private val OptionsPlayButtonShape = AbsoluteSmoothCornerShape(
    cornerRadiusTR = 60.dp, smoothnessAsPercentTR = 60,
    cornerRadiusBR = 60.dp, smoothnessAsPercentBR = 60,
    cornerRadiusBL = 60.dp, smoothnessAsPercentBL = 60,
    cornerRadiusTL = 60.dp, smoothnessAsPercentTL = 60,
)
private val OptionsArtShape = AbsoluteSmoothCornerShape(
    cornerRadiusTR = 20.dp, smoothnessAsPercentTR = 60,
    cornerRadiusBR = 20.dp, smoothnessAsPercentBR = 60,
    cornerRadiusBL = 20.dp, smoothnessAsPercentBL = 60,
    cornerRadiusTL = 20.dp, smoothnessAsPercentTL = 60,
)

@Composable
private fun OptionsSheetHeader(artwork: String?, title: String, subtitle: String, artShape: Shape = OptionsArtShape) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmartImage(
            model = artwork,
            contentDescription = stringResource(R.string.common_album_art),
            shape = artShape,
            modifier = Modifier.size(72.dp),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) Text(
                text = subtitle,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RadioAndPinRow(item: Music, playerViewModel: PlayerViewModel, onStartRadio: (() -> Unit)?) {
    val pins by playerViewModel.model.pins.collectAsStateWithLifecycle()
    val isPinned = pins.any { it.id == item.id }
    val downloads by playerViewModel.downloadCollections.collectAsStateWithLifecycle()
    val preparations by playerViewModel.downloadPreparations.collectAsStateWithLifecycle()
    val isLocal = fr.spotifeur.data.isLocalTrackId(item.id)
    if (!isLocal) {
        val entry = downloads[item.id]
        FilledTonalButton(
            onClick = { playerViewModel.toggleDownloadItem(item) },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Icon(if (entry?.isComplete == true) Icons.Rounded.DownloadDone else Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    item.id in preparations -> stringResource(R.string.download_cancel)
                    entry == null -> stringResource(R.string.download_action)
                    entry.failed -> stringResource(R.string.download_cancel)
                    entry.isComplete -> stringResource(R.string.download_remove)
                    else -> stringResource(R.string.download_progress, entry.percent.toInt().coerceAtLeast(0))
                },
                maxLines = 1,
            )
        }
    }
    var addingToPlaylist by remember { mutableStateOf(false) }
    if (addingToPlaylist) AddToLocalPlaylistSheet(item, playerViewModel) { addingToPlaylist = false }
    FilledTonalButton(
        onClick = { addingToPlaylist = true },
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(26.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.playlist_add_to), maxLines = 1)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onStartRadio != null) FilledTonalButton(
            onClick = onStartRadio,
            modifier = Modifier.weight(1f).height(58.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Icon(Icons.Rounded.Radio, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.radio), maxLines = 1)
        }
        FilledTonalButton(
            onClick = { playerViewModel.togglePin(item) },
            modifier = Modifier.weight(if (onStartRadio != null) 1.4f else 1f).height(58.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Icon(if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isPinned) stringResource(R.string.pin_remove) else stringResource(R.string.pin_add), maxLines = 1)
        }
    }
}

/** The same menu as a track, for an album, artist or playlist of the library. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EntityOptionsSheet(
    item: Music,
    playerViewModel: PlayerViewModel,
    onOpen: () -> Unit,
    onStartRadio: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val saved by playerViewModel.model.favorites.collectAsStateWithLifecycle()
    val isLiked = item.id == playerViewModel.likedSongs.id
    val canSave = item.kind == MusicKind.Album || item.kind == MusicKind.Artist
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OptionsSheetHeader(
                artwork = item.imageUrl,
                title = item.title,
                subtitle = item.subtitle.ifBlank { item.kind.label },
                artShape = if (item.kind == MusicKind.Artist) CircleShape else OptionsArtShape,
            )
            Row1Actions(
                isFavorite = item.id in saved,
                onPlaySong = { playerViewModel.playItem(item); onDismiss() },
                onToggleFavorite = if (canSave) ({ playerViewModel.model.toggleFavorite(item) }) else null,
                onShareClick = if (item.spotifyShareUrl() != null) ({ context.shareMusic(item) }) else null,
                playButtonShape = OptionsPlayButtonShape,
                evenCornerRadiusElems = 26.dp
            )
            Row2Actions(
                onAddToQueue = { playerViewModel.addItemToQueue(item); onDismiss() },
                onAddNextToQueue = { playerViewModel.addItemNext(item); onDismiss() },
            )
            FilledTonalButton(
                onClick = { playerViewModel.shuffleItem(item); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.common_shuffle), maxLines = 1)
            }
            RadioAndPinRow(item, playerViewModel, if (isLiked || item.id.startsWith("local:")) null else onStartRadio)
            FilledTonalButton(
                onClick = { onDismiss(); onOpen() },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                Icon(
                    when (item.kind) {
                        MusicKind.Artist -> Icons.Rounded.Person
                        MusicKind.Album -> Icons.Rounded.Album
                        else -> Icons.AutoMirrored.Rounded.QueueMusic
                    }, contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (item.kind) {
                        MusicKind.Artist -> stringResource(R.string.open_artist)
                        MusicKind.Album -> stringResource(R.string.open_album)
                        else -> stringResource(R.string.open_playlist)
                    }, maxLines = 1,
                )
            }
            if (item.kind == MusicKind.Playlist) {
                val library by playerViewModel.model.library.collectAsStateWithLifecycle()
                val discovery by playerViewModel.model.discovery.collectAsStateWithLifecycle()
                val export = rememberPlaylistCsvExport(playerViewModel.model)
                var deleting by remember { mutableStateOf(false) }
                val youTubePlaylists by playerViewModel.model.youTubePlaylists.collectAsStateWithLifecycle()
                val inLibrary = item.isLocalPlaylist || (discovery.spotifyEnabled && item.id.startsWith("spotify:playlist:") &&
                    library.content?.playlists?.any { it.id == item.id } == true) || youTubePlaylists.any { it.id == item.id }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { export(item) },
                        modifier = Modifier.weight(1f).height(58.dp),
                        shape = RoundedCornerShape(26.dp),
                    ) {
                        Icon(Icons.Rounded.TableChart, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.playlist_export_csv_short), maxLines = 1)
                    }
                    if (inLibrary) FilledTonalButton(
                        onClick = { deleting = true },
                        modifier = Modifier.weight(1f).height(58.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_delete), maxLines = 1)
                    }
                }
                if (deleting) DeletePlaylistDialog(playerViewModel.model, item, onDismiss = { deleting = false; onDismiss() })
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SongInfoSegmentedListItem(
    headline: String,
    supporting: String,
    icon: ImageVector,
    iconDescription: String,
    shape: Shape,
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .let { baseModifier ->
            if (onClick != null) {
                baseModifier.clickable(onClick = onClick)
            } else {
                baseModifier
            }
        }

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(headline) },
            supportingContent = { Text(supporting) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = iconDescription,
                )
            }
        )
    }
}

@Composable
private fun Row1Actions(
    isFavorite: Boolean,
    onPlaySong: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    onShareClick: (() -> Unit)?,
    playButtonShape: Shape,
    evenCornerRadiusElems: androidx.compose.ui.unit.Dp
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var clickPending by remember { mutableStateOf(false) }

    val playInteractionSource = remember { MutableInteractionSource() }
    val isPlayPressed by playInteractionSource.collectIsPressedAsState()
    var playVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isPlayPressed) {
        if (isPlayPressed) {
            playVisualPressed = true
        } else {
            kotlinx.coroutines.delay(180)
            playVisualPressed = false
        }
    }

    val favoriteInteractionSource = remember { MutableInteractionSource() }
    val isFavoritePressed by favoriteInteractionSource.collectIsPressedAsState()
    var favoriteVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isFavoritePressed) {
        if (isFavoritePressed) {
            favoriteVisualPressed = true
        } else {
            kotlinx.coroutines.delay(180)
            favoriteVisualPressed = false
        }
    }

    val shareInteractionSource = remember { MutableInteractionSource() }
    val isSharePressed by shareInteractionSource.collectIsPressedAsState()
    var shareVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isSharePressed) {
        if (isSharePressed) {
            shareVisualPressed = true
        } else {
            kotlinx.coroutines.delay(180)
            shareVisualPressed = false
        }
    }

    val pressSpec = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = 300f
    )

    val pressFractionPlay by animateFloatAsState(
        targetValue = if (playVisualPressed) 1f else 0f,
        animationSpec = pressSpec,
        label = "PlayPressFraction"
    )
    val pressFractionFavorite by animateFloatAsState(
        targetValue = if (favoriteVisualPressed) 1f else 0f,
        animationSpec = pressSpec,
        label = "FavoritePressFraction"
    )
    val pressFractionShare by animateFloatAsState(
        targetValue = if (shareVisualPressed) 1f else 0f,
        animationSpec = pressSpec,
        label = "SharePressFraction"
    )

    val weightPlay = (0.5f + 0.08f * pressFractionPlay - 0.05f * pressFractionFavorite - 0.05f * pressFractionShare).coerceAtLeast(0.1f)
    val weightFavorite = (0.25f + 0.08f * pressFractionFavorite - 0.04f * pressFractionPlay - 0.03f * pressFractionShare).coerceAtLeast(0.05f)
    val weightShare = (0.25f + 0.08f * pressFractionShare - 0.04f * pressFractionPlay - 0.03f * pressFractionFavorite).coerceAtLeast(0.05f)

    // Local favorite button animations
    val favoriteButtonCornerRadius by animateDpAsState(
        targetValue = if (isFavorite) evenCornerRadiusElems else 60.dp,
        animationSpec = tween(durationMillis = 300), label = "FavoriteCornerAnimation"
    )
    val favoriteButtonContainerColor by animateColorAsState(
        targetValue = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 300), label = "FavoriteContainerColorAnimation"
    )
    val favoriteButtonContentColor by animateColorAsState(
        targetValue = if (isFavorite) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 300), label = "FavoriteContentColorAnimation"
    )

    val favoriteButtonShape = remember(favoriteButtonCornerRadius) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = favoriteButtonCornerRadius, smoothnessAsPercentBR = 60, cornerRadiusBR = favoriteButtonCornerRadius,
            smoothnessAsPercentTL = 60, cornerRadiusTL = favoriteButtonCornerRadius, smoothnessAsPercentBL = 60,
            cornerRadiusBL = favoriteButtonCornerRadius, smoothnessAsPercentTR = 60
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FilledTonalButton(
            modifier = Modifier
                .weight(weightPlay)
                .heightIn(min = 80.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
            shape = playButtonShape,
            interactionSource = playInteractionSource,
            onClick = {
                if (clickPending) return@FilledTonalButton
                clickPending = true
                playVisualPressed = true
                coroutineScope.launch {
                    kotlinx.coroutines.delay(180)
                    playVisualPressed = false
                    clickPending = false
                    onPlaySong()
                }
            }
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.song_info_cd_play),
            )
            Spacer(Modifier.width(6.dp))
            TightWrapText(
                text = stringResource(R.string.song_info_action_play),
                modifier = Modifier.padding(end = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                lineHeight = 22.sp,
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (onToggleFavorite != null) FilledIconButton(
            modifier = Modifier
                .weight(weightFavorite)
                .fillMaxHeight(),
            onClick = {
                if (clickPending) return@FilledIconButton
                clickPending = true
                favoriteVisualPressed = true
                coroutineScope.launch {
                    kotlinx.coroutines.delay(180)
                    favoriteVisualPressed = false
                    clickPending = false
                    onToggleFavorite()
                }
            },
            shape = favoriteButtonShape,
            interactionSource = favoriteInteractionSource,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = favoriteButtonContainerColor,
                contentColor = favoriteButtonContentColor
            )
        ) {
            Icon(
                modifier = Modifier.size(FloatingActionButtonDefaults.LargeIconSize),
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.song_info_cd_remove_from_favorites else R.string.song_info_cd_add_to_favorites
                )
            )
        }

        if (onShareClick != null) FilledTonalIconButton(
            modifier = Modifier
                .weight(weightShare)
                .fillMaxHeight(),
            onClick = {
                if (clickPending) return@FilledTonalIconButton
                clickPending = true
                shareVisualPressed = true
                coroutineScope.launch {
                    kotlinx.coroutines.delay(180)
                    shareVisualPressed = false
                    clickPending = false
                    onShareClick()
                }
            },
            shape = CircleShape,
            interactionSource = shareInteractionSource
        ) {
            Icon(
                modifier = Modifier.size(FloatingActionButtonDefaults.LargeIconSize),
                imageVector = Icons.Rounded.Share,
                contentDescription = stringResource(R.string.song_info_cd_share_song_file)
            )
        }
    }
}

@Composable
private fun Row2Actions(
    onAddToQueue: () -> Unit,
    onAddNextToQueue: () -> Unit
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var clickPending by remember { mutableStateOf(false) }

    val queueInteractionSource = remember { MutableInteractionSource() }
    val isQueuePressed by queueInteractionSource.collectIsPressedAsState()
    var queueVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isQueuePressed) {
        if (isQueuePressed) {
            queueVisualPressed = true
        } else {
            kotlinx.coroutines.delay(180)
            queueVisualPressed = false
        }
    }

    val nextInteractionSource = remember { MutableInteractionSource() }
    val isNextPressed by nextInteractionSource.collectIsPressedAsState()
    var nextVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isNextPressed) {
        if (isNextPressed) {
            nextVisualPressed = true
        } else {
            kotlinx.coroutines.delay(180)
            nextVisualPressed = false
        }
    }

    val pressSpec = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = 300f
    )

    val pressFractionQueue by animateFloatAsState(
        targetValue = if (queueVisualPressed) 1f else 0f,
        animationSpec = pressSpec,
        label = "QueuePressFraction"
    )
    val pressFractionNext by animateFloatAsState(
        targetValue = if (nextVisualPressed) 1f else 0f,
        animationSpec = pressSpec,
        label = "NextPressFraction"
    )

    val weightQueue = (0.6f + 0.08f * pressFractionQueue - 0.08f * pressFractionNext).coerceAtLeast(0.1f)
    val weightNext = (0.4f + 0.08f * pressFractionNext - 0.08f * pressFractionQueue).coerceAtLeast(0.1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FilledTonalButton(
            modifier = Modifier
                .weight(weightQueue)
                .heightIn(min = 66.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
            shape = CircleShape,
            interactionSource = queueInteractionSource,
            onClick = {
                if (clickPending) return@FilledTonalButton
                clickPending = true
                queueVisualPressed = true
                coroutineScope.launch {
                    kotlinx.coroutines.delay(180)
                    queueVisualPressed = false
                    clickPending = false
                    onAddToQueue()
                }
            }
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = stringResource(R.string.song_info_cd_add_to_queue),
            )
            Spacer(Modifier.width(6.dp))
            TightWrapText(
                text = stringResource(R.string.song_info_action_add_to_queue),
                modifier = Modifier.padding(end = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                lineHeight = 20.sp
            )
        }

        FilledTonalButton(
            modifier = Modifier
                .weight(weightNext)
                .heightIn(min = 66.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
            shape = CircleShape,
            interactionSource = nextInteractionSource,
            onClick = {
                if (clickPending) return@FilledTonalButton
                clickPending = true
                nextVisualPressed = true
                coroutineScope.launch {
                    kotlinx.coroutines.delay(180)
                    nextVisualPressed = false
                    clickPending = false
                    onAddNextToQueue()
                }
            }
        ) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = stringResource(R.string.song_info_cd_queue_next)
            )
            Spacer(Modifier.width(6.dp))
            TightWrapText(
                text = stringResource(R.string.song_info_action_queue_next),
                modifier = Modifier.padding(end = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                lineHeight = 20.sp
            )
        }
    }
}
