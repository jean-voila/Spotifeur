package fr.spotifeur.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.utils.formatSongCount
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.model.toSong
import fr.spotifeur.data.preferences.CollagePattern
import fr.spotifeur.presentation.components.AlbumArtCollage
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.toImmutableList
import fr.spotifeur.data.preferences.HomeShelf
import fr.spotifeur.data.preferences.ShelfStyle
import fr.spotifeur.presentation.components.HomeGradientTopBar
import fr.spotifeur.presentation.components.MiniPlayerHeight
import fr.spotifeur.presentation.components.MusicOptionsHost
import fr.spotifeur.presentation.components.SmartImage
import fr.spotifeur.presentation.components.SmartImageCompactListTargetSize
import fr.spotifeur.presentation.components.resolveMainScreenBottomGradientHeight
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.presentation.navigation.navigateToTopLevelSafely
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import sh.calvin.reorderable.ReorderableColumn

private const val COLLAGE_ITEMS = 6
private const val COLLAGE_MINIMUM = 3

/**
 * The home canvas, with generated mixes and statistics replaced by what this app is
 * for: the items pinned on this device first, then the Spotify library.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(model: SpotifeurModel, navController: NavHostController, paddingValues: PaddingValues) {
    val ui = model.ui
    val pins by model.pins.collectAsStateWithLifecycle()
    val downloads by ui.downloadCollections.collectAsStateWithLifecycle()
    val downloadsTitle = stringResource(R.string.home_downloads)
    val settings by ui.settings.collectAsStateWithLifecycle()
    val localPlaylists by model.localPlaylists.collectAsStateWithLifecycle()
    val youTubePlaylists by model.youTubePlaylists.collectAsStateWithLifecycle()
    val localBadge = stringResource(R.string.badge_local)
    val playlistsTitle = stringResource(R.string.home_playlists)
    val albumsTitle = stringResource(R.string.home_albums)
    val artistsTitle = stringResource(R.string.home_artists)
    val recent by model.recent.collectAsStateWithLifecycle()
    val localFiles by model.localTracks.collectAsStateWithLifecycle()
    val shelfTitles = mapOf(
        HomeShelf.PINS to stringResource(R.string.home_pins),
        HomeShelf.RECENT to stringResource(R.string.home_recent),
        HomeShelf.MULTI_SERVICE to stringResource(R.string.home_multi_service),
        HomeShelf.SPOTIFY_PLAYLISTS to stringResource(R.string.home_spotify_playlists),
        HomeShelf.YOUTUBE_PLAYLISTS to stringResource(R.string.home_youtube_playlists),
        HomeShelf.LOCAL_FILES to stringResource(R.string.home_local_files),
    )
    val library by model.library.collectAsStateWithLifecycle()
    val hasSong by ui.stablePlayerStateHasSong()
    val navBarCompactMode by ui.navBarCompactMode.collectAsStateWithLifecycle()
    val content = library.content
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val scrollThresholdPx = remember(density) { with(density) { 180.dp.toPx() } }
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > scrollThresholdPx }
    }
    var reordering by rememberSaveable { mutableStateOf(false) }
    val open: (Music) -> Unit = { navController.openMusic(ui, it) }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val showOptions: (Music) -> Unit = {
        // The long-click handler of the card already vibrates.
        optionsFor = it
    }
    val openLibrary: () -> Unit = { navController.navigateToTopLevelSafely(Screen.Library.route) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                HomeGradientTopBar(
                    onSettingsClick = { navController.navigateSafely(Screen.Settings.route) },
                    onHistoryClick = { navController.navigateSafely(Screen.History.route) },
                    isScrolled = isScrolled,
                )
            }
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = library.loading && content != null,
                onRefresh = model::refreshLibrary,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding() + 38.dp + if (hasSong) MiniPlayerHeight else 0.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    if (content == null && library.loading) {
                        item(key = "library_loading") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                ContainedLoadingIndicator()
                            }
                        }
                    }
                    library.error?.let { message ->
                        item(key = "library_error") {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(24.dp),
                                onClick = model::refreshLibrary,
                            ) {
                                Text(
                                    message,
                                    Modifier.padding(18.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    // Shelves in the order, layout and size chosen in Settings → Home.
                    settings.homeLayout.filter { it.visible }.forEach { config ->
                        val multi = localPlaylists.map { it.asMusic().copy(subtitle = localBadge + " · " + it.asMusic().subtitle) }
                        val openTab: (fr.spotifeur.data.model.LibraryTabId) -> () -> Unit = { tab -> { ui.requestedLibraryTab.value = tab.storageKey; openLibrary() } }
                        val title = when (config.shelf) {
                            HomeShelf.DOWNLOADS -> downloadsTitle
                            HomeShelf.PLAYLISTS -> playlistsTitle
                            HomeShelf.ALBUMS -> albumsTitle
                            HomeShelf.ARTISTS -> artistsTitle
                            else -> shelfTitles[config.shelf].orEmpty()
                        }
                        val (items, openAll) = when (config.shelf) {
                            HomeShelf.PINS -> pins to null
                            HomeShelf.RECENT -> recent to null
                            HomeShelf.DOWNLOADS -> downloads.values.filter { it.isComplete }.map { it.collection.item } to openTab(fr.spotifeur.data.model.LibraryTabId.DOWNLOADS)
                            HomeShelf.LIKED -> emptyList<Music>() to null
                            HomeShelf.PLAYLISTS -> (multi + youTubePlaylists + content?.playlists.orEmpty()) to openTab(fr.spotifeur.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.MULTI_SERVICE -> multi to openTab(fr.spotifeur.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.SPOTIFY_PLAYLISTS -> content?.playlists.orEmpty() to openTab(fr.spotifeur.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.YOUTUBE_PLAYLISTS -> youTubePlaylists to openTab(fr.spotifeur.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.ALBUMS -> content?.albums.orEmpty() to openTab(fr.spotifeur.data.model.LibraryTabId.ALBUMS)
                            HomeShelf.ARTISTS -> content?.artists.orEmpty() to openTab(fr.spotifeur.data.model.LibraryTabId.ARTISTS)
                            HomeShelf.LOCAL_FILES -> localFiles to openTab(fr.spotifeur.data.model.LibraryTabId.LOCAL)
                        }
                        when {
                            config.shelf == HomeShelf.LIKED -> if (content != null && content.savedTrackCount > 0) item(key = "liked", contentType = "liked") {
                                LikedSongsCard(
                                    count = content.savedTrackCount,
                                    artworks = content.savedTracks.mapNotNull(Music::imageUrl).distinct().take(3),
                                    onOpen = { open(ui.likedSongs) },
                                    onLongPress = { showOptions(ui.likedSongs) },
                                    onPlay = {
                                        // The count is known before the tracks: without them, the list itself is opened.
                                        val first = content.savedTracks.firstOrNull()
                                        if (first == null) open(ui.likedSongs)
                                        else ui.playSongs(content.savedTracks.map { it.toSong() }, first.toSong(), ui.likedSongs.title)
                                    },
                                )
                            }
                            config.shelf == HomeShelf.PINS && config.style == ShelfStyle.COLLAGE -> if (pins.isNotEmpty()) {
                                item(key = "pins_collage", contentType = "pins_collage") {
                                    PinsCollage(pins.take(config.count), settings.collagePattern, settings.collageAutoRotate, ui, { open(it) }, { showOptions(it) }, { reordering = true })
                                }
                            }
                            else -> homeShelf(config.shelf.key, title.takeIf { config.showTitle }, items.take(config.count), config.style, ui,
                                onClick = { item ->
                                    val tracks = items.filter { it.kind == MusicKind.Track }
                                    if (item.kind == MusicKind.Track && tracks.size > 1) ui.playSongs(tracks.map { it.toSong() }, item.toSong(), title)
                                    else if (item.kind == MusicKind.Track) ui.playItem(item) else open(item)
                                },
                                onLongPress = showOptions, openAll = openAll)
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(resolveMainScreenBottomGradientHeight(navBarCompactMode))
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.8f to MaterialTheme.colorScheme.surfaceContainerLowest,
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        )
    }

    if (reordering) PinsReorderSheet(pins, ui, model, onDismiss = { reordering = false })
    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
}

@Composable
private fun PlayerViewModel.stablePlayerStateHasSong() = remember(this) {
    derivedHasSong
}.collectAsStateWithLifecycle()

private fun androidx.compose.foundation.lazy.LazyListScope.homeShelf(
    key: String,
    title: String?,
    items: List<Music>,
    style: ShelfStyle,
    ui: PlayerViewModel,
    onClick: (Music) -> Unit,
    onLongPress: (Music) -> Unit,
    openAll: (() -> Unit)?,
) {
    if (items.isEmpty()) return
    item(key = key, contentType = "shelf_${style.key}") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) SectionHeader(title, openAll)
            ShelfItems(items, style, ui, onClick, onLongPress) { item, modifier ->
                PinnedPill(item, ui, modifier, { onClick(item) }, { onLongPress(item) })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onOpen: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.padding(start = 6.dp),
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        if (onOpen != null) FilledIconButton(
            modifier = Modifier.height(40.dp).width(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.secondary
            ),
            onClick = onOpen,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = title,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PinsCollage(
    pins: List<Music>,
    patternKey: String,
    autoRotate: Boolean,
    ui: PlayerViewModel,
    open: (Music) -> Unit,
    showOptions: (Music) -> Unit,
    onReorder: () -> Unit,
) {
    val songs = remember(pins) { pins.take(COLLAGE_ITEMS).map { it.toSong() }.toImmutableList() }
    val pattern = remember(patternKey, autoRotate) {
        if (autoRotate) CollagePattern.entries.random() else CollagePattern.fromStorageKey(patternKey)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A collage only shows artwork: it needs enough elements to read as one.
        if (songs.size >= COLLAGE_MINIMUM) AlbumArtCollage(
            modifier = Modifier.fillMaxWidth(),
            songs = songs,
            padding = 14.dp,
            height = 400.dp,
            pattern = pattern,
            onSongClick = { song -> pins.firstOrNull { it.id == song.id }?.let(open) },
        )
        pins.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    PinnedPill(item, ui, Modifier.weight(1f), { open(item) }, { showOptions(item) })
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (pins.size > 1) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterEnd) {
            FilledIconButton(
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.secondary,
                ),
                onClick = onReorder,
            ) {
                Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.pins_reorder))
            }
        }
    }
}

private val PinPillHeight = 64.dp
private val PinPillArtSize = 52.dp

/** The recently played pill, tinted from the artwork. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedPill(
    item: Music,
    ui: PlayerViewModel,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val schemeState by remember(item.imageUrl) {
        ui.getAlbumColorSchemeFlow(item.imageUrl.orEmpty())
    }.collectAsStateWithLifecycle()
    val scheme = remember(schemeState, isDark) { schemeState?.let { if (isDark) it.dark else it.light } }
    val container by animateColorAsState(
        scheme?.primaryContainer ?: MaterialTheme.colorScheme.primaryContainer, tween(280), label = "pinContainer"
    )
    val title by animateColorAsState(
        scheme?.onPrimaryContainer ?: MaterialTheme.colorScheme.onPrimaryContainer, tween(280), label = "pinTitle"
    )
    val shape = RoundedCornerShape(PinPillHeight / 2)
    val artPadding = (PinPillHeight - PinPillArtSize) / 2
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .height(PinPillHeight)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = artPadding, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (item.id == ui.likedSongs.id) {
                Box(
                    Modifier.size(PinPillArtSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                SmartImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    shape = CircleShape,
                    targetSize = SmartImageCompactListTargetSize,
                    modifier = Modifier.size(PinPillArtSize)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = title
                )
                Text(
                    text = item.subtitle.ifBlank { item.kind.label },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = title.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LikedSongsCard(
    count: Int,
    artworks: List<String>,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onPlay: () -> Unit,
) {
    val shape = RoundedCornerShape(30.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = shape,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (artworks.isNotEmpty()) SmartImage(
                    model = artworks.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)))
                Icon(Icons.Rounded.Favorite, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(stringResource(R.string.liked_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    formatSongCount(count),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Icon(Icons.Rounded.PlayArrow, stringResource(R.string.liked_play), Modifier.size(30.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinsReorderSheet(pins: List<Music>, ui: PlayerViewModel, model: SpotifeurModel, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.reorder),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        ReorderableColumn(
            list = pins,
            onSettle = { from, to -> model.movePin(from, to) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { _, item, _ ->
            key(item.id) {
            ReorderableItem {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmartImage(
                            model = item.imageUrl,
                            contentDescription = null,
                            shape = RoundedCornerShape(12.dp),
                            contentScale = ContentScale.Crop,
                            targetSize = SmartImageCompactListTargetSize,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            item.title,
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { ui.togglePin(item) }) {
                            Icon(Icons.Outlined.PushPin, stringResource(R.string.pin_remove))
                        }
                        IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                            Icon(Icons.Rounded.DragHandle, stringResource(R.string.reorder))
                        }
                    }
                }
            }
            }
        }
    }
}
