package fr.spotifeur.presentation.screens

import fr.spotifeur.data.source
import fr.spotifeur.presentation.components.MusicOptionsHost
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.res.stringResource
import fr.spotifeur.ui.theme.SpotifeurStatusBarStyle
import fr.spotifeur.utils.formatSongCount
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.navigation.NavHostController
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.model.toAlbum
import fr.spotifeur.data.model.toArtist
import fr.spotifeur.data.model.toSong
import fr.spotifeur.presentation.components.ExpressiveScrollBar
import fr.spotifeur.presentation.components.MiniPlayerHeight
import fr.spotifeur.presentation.components.SmartImage
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.components.subcomps.EnhancedSongListItem
import fr.spotifeur.presentation.components.subcomps.AutoSizingTextToFill
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.ui.theme.LocalSpotifeurDarkTheme
import fr.spotifeur.ui.theme.LocalShowScrollbar
import fr.spotifeur.ui.theme.GoogleSansRounded
import fr.spotifeur.R
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.Album
import fr.spotifeur.data.model.Artist
import fr.spotifeur.data.model.Playlist
import fr.spotifeur.presentation.viewmodel.ColorSchemePair
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import fr.spotifeur.presentation.components.ShimmerBox
import fr.spotifeur.presentation.components.AutoScrollingTextOnDemand
import fr.spotifeur.presentation.components.subcomps.TightWrapText
import fr.spotifeur.utils.formatDuration
import fr.spotifeur.ui.theme.ExpTitleTypography
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import fr.spotifeur.presentation.components.AlbumListItem
import fr.spotifeur.presentation.components.ArtistListItem
import androidx.compose.material3.ContainedLoadingIndicator






@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistDetailScreen(
    model: SpotifeurModel,
    navController: NavHostController,
    artistId: Long,
) {
    val ui = model.ui
    val item = remember(artistId) { ui.entity(artistId) }
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
    val lazyListState = rememberLazyListState()
    var selectedSong by remember { mutableStateOf<Song?>(null) }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val isDarkTheme = LocalSpotifeurDarkTheme.current
    val baseColorScheme = MaterialTheme.colorScheme
    val artistColorSchemePair by remember(current.imageUrl) {
        ui.getAlbumColorSchemeFlow(current.imageUrl.orEmpty())
    }.collectAsStateWithLifecycle()
    val artistColorScheme = remember(artistColorSchemePair, isDarkTheme) {
        artistColorSchemePair?.let { pair -> if (isDarkTheme) pair.dark else pair.light } ?: baseColorScheme
    }
    LaunchedEffect(Unit) {
        ui.collapsePlayerSheet()
    }

    // --- Lógica del Header Colapsable ---
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 300.dp

    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    val headerImageRequestSize = remember(
        configuration.screenWidthDp,
        density.density,
        maxTopBarHeightPx
    ) {
        Size(
            width = with(density) { configuration.screenWidthDp.dp.roundToPx() },
            height = maxTopBarHeightPx.roundToInt()
        )
    }

    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    val collapseFraction by remember(minTopBarHeightPx, maxTopBarHeightPx) {
        derivedStateOf {
            1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0

                // Si estamos scrolleando hacia arriba y no estamos en el tope de la lista,
                // el scroll es para la lista, no para la TopBar.
                if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }

                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight

                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch {
                        topBarHeight.snapTo(newHeight)
                    }
                }

                // Si estamos en el tope y scrolleamos hacia arriba, la lista no debe moverse.
                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return super.onPostFling(consumed, available)
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0

            val targetValue = if (shouldExpand && canExpand) {
                maxTopBarHeightPx
            } else {
                minTopBarHeightPx
            }

            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }
    // --- Fin de la lógica del Header ---

    // Wrap in dynamic theme derived from the artist's image
    MaterialTheme(
        colorScheme = artistColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.nestedScroll(nestedScrollConnection)) {
            val artist = current.toArtist()
            val songs = remember(detail?.content?.tracks, saved) {
                detail?.content?.tracks.orEmpty().map { it.toSong(it.id in saved) }
            }
            val related = detail?.content?.related.orEmpty().filter { it.id != current.id }
            val albums = related.filter { it.kind == MusicKind.Album }
            val artists = related.filter { it.kind == MusicKind.Artist }
            val profile = detail?.content?.artist
            val exact = remember { java.text.NumberFormat.getIntegerInstance() }
            val short = remember { android.icu.text.CompactDecimalFormat.getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT) }
            val numbers = remember(profile?.approximate) { { value: Long -> if (profile?.approximate == true) short.format(value) else exact.format(value) } }
            // Release groups as the official page shows them; pages stored before profiles keep one list.
            val albumsById = remember(albums) { albums.associateBy(Music::id) }
            val sections = remember(profile, albums) {
                if (profile == null) listOf(R.string.albums to albums)
                else listOf(
                    R.string.artist_popular_releases to profile.popularReleases,
                    R.string.albums to profile.albums,
                    R.string.artist_singles to profile.singles,
                    R.string.artist_compilations to profile.compilations,
                    R.string.artist_appears_on to profile.appearsOn,
                ).map { (title, ids) -> title to ids.mapNotNull(albumsById::get) }
            }.filter { it.second.isNotEmpty() }
            val expanded = remember(item.id) { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
            val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }
            val showScrollBar = LocalShowScrollbar.current && collapseFraction > 0.95f &&
                (lazyListState.canScrollForward || lazyListState.canScrollBackward)
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        val extraHeight = (topBarHeight.value - minTopBarHeightPx).roundToInt()
                        IntOffset(0, extraHeight)
                    },
                contentPadding = PaddingValues(
                    top = minTopBarHeight + 8.dp,
                    start = 16.dp,
                    end = if (showScrollBar) 24.dp else 16.dp,
                    bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp
                ),
            ) {
                item(key = "artist_actions") {
                    DetailActionsRow(
                        item = current,
                        canSave = current.source == fr.spotifeur.data.MusicSource.Spotify,
                        showRadio = current.source == fr.spotifeur.data.MusicSource.Spotify,
                        isSaved = current.id in saved,
                        isPinned = pins.any { it.id == current.id },
                        onSave = { model.toggleFavorite(current) },
                        onPin = { ui.togglePin(current) },
                        onRadio = { ui.startRadio(current) { navController.openMusic(ui, it) } },
                        ui = ui,
                    )
                }
                profile?.monthlyListeners?.let { listeners ->
                    item(key = "artist_listeners") {
                        Text(
                            stringResource(R.string.artist_monthly_listeners, numbers(listeners)),
                            Modifier.padding(start = 4.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (songs.isNotEmpty()) item(key = "artist_popular_header") { ArtistSectionTitle(stringResource(R.string.artist_popular)) }
                if (songs.isEmpty() && detail?.loading != false) item(key = "artist_loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                }
                detail?.error?.let { message ->
                    if (detail.content == null) item(key = "artist_error") {
                        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                itemsIndexed(songs, key = { index, song -> "artist_song_${index}_${song.id}" }, contentType = { _, _ -> "artist_song" }) { index, song ->
                    val isCurrent = stablePlayerState.currentSong?.id == song.id
                    ArtistAlbumSectionSongItem(
                        song = song,
                        songIndex = index,
                        songCount = songs.size,
                        isCurrentSong = isCurrent,
                        isPlaying = isCurrent && stablePlayerState.isPlaying,
                        onSongClick = { ui.playSongs(songs, song, artist.name) },
                        onMoreOptionsClick = { selectedSong = song },
                        supportingText = profile?.playCounts?.get(song.id)?.let(numbers),
                    )
                }
                sections.forEach { (title, list) ->
                    val open = expanded[title] == true
                    val shown = if (open) list else list.take(SECTION_PREVIEW)
                    item(key = "artist_section_$title") { ArtistSectionTitle(stringResource(title)) }
                    items(shown, key = { "artist_section_${title}_" + it.id }, contentType = { "artist_album" }) { album ->
                        Box(Modifier.padding(bottom = 8.dp)) { AlbumListItem(
                            album = album.toAlbum(),
                            albumColorSchemePairFlow = ui.getAlbumColorSchemeFlow(album.imageUrl.orEmpty()),
                            onClick = { navController.openMusic(ui, album) },
                            onLongPress = { optionsFor = album },
                        ) }
                    }
                    if (list.size > SECTION_PREVIEW) item(key = "artist_section_more_$title") {
                        androidx.compose.material3.TextButton(onClick = { expanded[title] = !open }) {
                            Text(if (open) stringResource(R.string.artist_show_less) else stringResource(R.string.artist_show_all, list.size))
                        }
                    }
                }
                if (artists.isNotEmpty()) {
                    item(key = "artist_related_header") { ArtistSectionTitle(stringResource(R.string.similar_artists)) }
                    val similarOpen = expanded[R.string.similar_artists] == true
                    items(if (similarOpen) artists else artists.take(SECTION_PREVIEW), key = { "artist_related_" + it.id }, contentType = { "artist_related" }) { other ->
                        Box(Modifier.padding(bottom = 8.dp)) { ArtistListItem(
                            artist = other.toArtist(),
                            onClick = { navController.openMusic(ui, other) },
                            onLongPress = { optionsFor = other },
                        ) }
                    }
                }
                if (artists.size > SECTION_PREVIEW) item(key = "artist_related_more") {
                    val similarOpen = expanded[R.string.similar_artists] == true
                    androidx.compose.material3.TextButton(onClick = { expanded[R.string.similar_artists] = !similarOpen }) {
                        Text(if (similarOpen) stringResource(R.string.artist_show_less) else stringResource(R.string.artist_show_all, artists.size))
                    }
                }
                val biography = profile?.biography?.takeIf(String::isNotBlank)
                if (biography != null || profile?.worldRank != null) item(key = "artist_about") {
                    ArtistAbout(biography, profile?.monthlyListeners?.let(numbers), profile?.worldRank, profile?.headerImageUrl)
                }
            }
            if (showScrollBar) {
                ExpressiveScrollBar(
                    listState = lazyListState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = minTopBarHeight + 12.dp, bottom = MiniPlayerHeight + 16.dp)
                )
            }
            CustomCollapsingTopBar(
                artist = artist,
                effectiveImageUrl = artist.effectiveImageUrl,
                songsCount = songs.size,
                collapseFraction = collapseFraction,
                headerHeight = currentTopBarHeightDp,
                headerImageRequestSize = headerImageRequestSize,
                onBackPressed = { navController.popBackStack() },
                onPlayClick = { ui.playSongsShuffled(songs, artist.name) },
            )
        }
    }
    }
    SongOptionsHost(ui, navController, selectedSong, detail?.content?.tracks.orEmpty().map { it.toSong() }, current.title) { selectedSong = null }
    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
}


private const val SECTION_PREVIEW = 6

@Composable
private fun ArtistSectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

/** The "About" card: header picture, monthly listeners, world rank and the biography, folded at first. */
@Composable
private fun ArtistAbout(biography: String?, listeners: String?, rank: Int?, image: String?) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        ArtistSectionTitle(stringResource(R.string.artist_about))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clickable(enabled = biography != null) { open = !open }) {
            Column {
                if (image != null) fr.spotifeur.presentation.components.SmartImage(
                    model = image, contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Column(Modifier.padding(16.dp)) {
                    if (rank != null) Text(stringResource(R.string.artist_world_rank, rank), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (listeners != null) Text(stringResource(R.string.artist_monthly_listeners, listeners), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (biography != null) Text(
                        biography, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (open) Int.MAX_VALUE else 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistAlbumSectionSongItem(
    modifier: Modifier = Modifier,
    song: Song,
    songIndex: Int,
    songCount: Int,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onSongClick: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    supportingText: String? = null,
) {
    val isLastSong = songIndex == songCount - 1

    val songItemShape = when {
        songCount == 1 -> RoundedCornerShape(16.dp)
        songIndex == 0 -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )
        isLastSong -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp
        )
        else -> RoundedCornerShape(4.dp)
    }

    val containerShape = if (isLastSong) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = 0.dp, smoothnessAsPercentTR = 0,
            cornerRadiusTL = 0.dp, smoothnessAsPercentTL = 0,
            cornerRadiusBR = 24.dp, smoothnessAsPercentBR = 60,
            cornerRadiusBL = 24.dp, smoothnessAsPercentBL = 60
        )
    } else {
        RectangleShape
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f), containerShape)
            .padding(horizontal = 8.dp)
    ) {
        Column {
            if (songIndex > 0) {
                Spacer(modifier = Modifier.height(2.dp))
            }

            EnhancedSongListItem(
                modifier = Modifier.fillMaxWidth(),
                song = song,
                isCurrentSong = isCurrentSong,
                isPlaying = isPlaying,
                showAlbumArt = true,
                customShape = songItemShape,
                supportingText = supportingText,
                onMoreOptionsClick = { onMoreOptionsClick() },
                onClick = onSongClick
            )

            if (isLastSong) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CustomCollapsingTopBar(
    artist: Artist,
    effectiveImageUrl: String?,
    songsCount: Int,
    collapseFraction: Float, // 0.0 = expandido, 1.0 = colapsado
    headerHeight: Dp,
    headerImageRequestSize: Size,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val statusBarColor = if (LocalSpotifeurDarkTheme.current) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.4f)

    // --- Animation Values ---
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
    val titleMaxLines = if(collapseFraction < 0.5f) 2 else 1
    val titleVerticalBias = lerp(1f, -1f, collapseFraction)
    val animatedTitleAlignment = BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias)
    val titleContainerHeight = lerp(88.dp, 56.dp, collapseFraction)
    val yOffsetCorrection = lerp( (titleContainerHeight / 2) - 64.dp, 0.dp, collapseFraction)


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
                val displayUrl = effectiveImageUrl?.takeIf { it.isNotBlank() }
                if (!displayUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(displayUrl)
                            .size(headerImageRequestSize)
                            .allowHardware(true)
                            .crossfade(false)
                            .build(),
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        alpha = headerContentAlpha,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    MusicIconPattern(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = headerContentAlpha }
                    )
                }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                FilledIconButton(
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 4.dp),
                    onClick = onBackPressed,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }



                // Box contenedor para el título
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
                            text = artist.name,
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
                            text = formatSongCount(songsCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Botón de Play
                LargeExtendedFloatingActionButton(
                    onClick = onPlayClick,
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

@Composable
private fun MusicIconPattern(modifier: Modifier = Modifier) {
    val color1 = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
    val color2 = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    Box(modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer)) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 76.dp, y = 72.dp)
                .size(60.dp)
                .graphicsLayer { rotationZ = -12f }
        )
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 4.dp, y = 78.dp)
                .size(50.dp)
                .graphicsLayer { rotationZ = 18f }
        )
        Icon(
            imageVector = Icons.Rounded.Album,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 4.dp, y = (-70).dp)
                .size(70.dp)
                .graphicsLayer { rotationZ = 12f }
        )
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = 14.dp, y = (-6).dp)
                .size(60.dp)
                .graphicsLayer { rotationZ = 14f }
        )
        Icon(
            imageVector = Icons.Rounded.SurroundSound,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(x = (-24).dp, y = 32.dp)
                .size(80.dp)
                .graphicsLayer { rotationZ = 10f }
        )
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-18).dp, y = (-138).dp)
                .size(45.dp)
                .graphicsLayer { rotationZ = -18f }
        )
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 72.dp, y = 34.dp)
                .size(45.dp)
                .graphicsLayer { rotationZ = -8f }
        )
    }
}
