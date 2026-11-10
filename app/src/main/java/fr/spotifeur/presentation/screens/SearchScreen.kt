package fr.spotifeur.presentation.screens

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.model.SearchFilterType
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toAlbum
import fr.spotifeur.data.model.toArtist
import fr.spotifeur.data.model.toPlaylist
import fr.spotifeur.data.model.toSong
import fr.spotifeur.presentation.components.MiniPlayerHeight
import fr.spotifeur.presentation.components.MusicOptionsHost
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.components.resolveMainScreenBottomGradientHeight
import fr.spotifeur.presentation.components.subcomps.EnhancedSongListItem
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.presentation.navigation.openMusic
import kotlinx.coroutines.flow.distinctUntilChanged

private fun SearchFilterType.kind(): MusicKind? = when (this) {
    SearchFilterType.ALL -> null
    SearchFilterType.SONGS -> MusicKind.Track
    SearchFilterType.ALBUMS -> MusicKind.Album
    SearchFilterType.ARTISTS -> MusicKind.Artist
    SearchFilterType.PLAYLISTS -> MusicKind.Playlist
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    model: SpotifeurModel,
    navController: NavHostController,
    paddingValues: PaddingValues,
    onSearchBarActiveChange: (Boolean) -> Unit,
) {
    val ui = model.ui
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val history by ui.searchHistory.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val navBarCompactMode by ui.navBarCompactMode.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf(discovery.query) }
    var filter by rememberSaveable { mutableStateOf(SearchFilterType.ALL) }
    var selected by remember { mutableStateOf<Song?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val statusBarTopInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val colorScheme = MaterialTheme.colorScheme
    val appSettings by ui.settings.collectAsStateWithLifecycle()
    val showSources = appSettings.showSearchSources
    // With the choice hidden, every search reads all sources.
    LaunchedEffect(showSources) {
        if (!showSources && discovery.source != fr.spotifeur.SearchSource.All) model.setSearchSource(fr.spotifeur.SearchSource.All)
    }
    val results = remember(discovery.results, filter) {
        val kind = filter.kind()
        discovery.results.filter { kind == null || it.kind == kind }
    }
    val songs = remember(results, saved) { results.filter { it.kind == MusicKind.Track }.map { it.toSong(it.id in saved) } }
    val listState = rememberLazyListState()
    val open: (Music) -> Unit = {
        ui.rememberSearchResult(it)
        navController.openMusic(ui, it)
    }
    val playFromHistory: (Music) -> Unit = { item ->
        ui.rememberSearchResult(item)
        if (item.kind == MusicKind.Track) item.toSong(item.id in saved).let { song -> ui.playSongs(listOf(song), song, item.title) }
        else navController.openMusic(ui, item)
    }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val showOptions: (Music) -> Unit = {
        // The long-click handler of the card already vibrates.
        optionsFor = it
    }
    LaunchedEffect(listState, results.size, discovery.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (discovery.hasMore && last >= listState.layoutInfo.totalItemsCount - 6) model.searchNext() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = statusBarTopInset + 12.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val inputColors = SearchBarDefaults.inputFieldColors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
                Box(Modifier.weight(1f).background(Color.Transparent)) {
                    DockedSearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = {
                                    searchQuery = it
                                    model.search(it)
                                },
                                onSearch = { query ->
                                    model.search(query, false)
                                    keyboard?.hide()
                                },
                                expanded = false,
                                onExpandedChange = { onSearchBarActiveChange(false) },
                                placeholder = {
                                    Text(
                                        stringResource(R.string.search_placeholder),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = stringResource(R.string.search_cd_search_icon),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                searchQuery = ""
                                                model.search("")
                                            },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = stringResource(R.string.search_cd_clear_search_query),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                colors = inputColors
                            )
                        },
                        expanded = false,
                        onExpandedChange = {},
                        modifier = Modifier.clip(RoundedCornerShape(28.dp)),
                        colors = SearchBarDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            dividerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            inputFieldColors = inputColors
                        ),
                        content = {}
                    )
                }
                FilledIconButton(
                    modifier = Modifier.padding(bottom = 2.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    onClick = { navController.navigateSafely(Screen.Settings.route) }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_settings_24),
                        contentDescription = stringResource(R.string.library_cd_open_settings)
                    )
                }
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (searchQuery.isBlank()) {
                    if (history.isNotEmpty()) {
                        SearchResultHistoryList(
                            items = history,
                            onOpen = playFromHistory,
                            onDelete = ui::forgetSearch,
                            onClearAll = ui::clearSearchHistory,
                            bottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp,
                        )
                    }
                } else {
                    // Everything at once by default; one catalogue, or the device, when the choice is shown (Settings → Behavior).
                    val sources = fr.spotifeur.SearchSource.entries.filter { it != fr.spotifeur.SearchSource.Spotify || discovery.spotifyEnabled }
                    if (showSources) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp)) {
                        sources.forEachIndexed { index, source ->
                            SegmentedButton(
                                selected = discovery.activeSource == source,
                                onClick = { model.setSearchSource(source) },
                                shape = SegmentedButtonDefaults.itemShape(index, sources.size),
                                icon = {},
                            ) {
                                Text(stringResource(when (source) {
                                    fr.spotifeur.SearchSource.All -> R.string.search_source_all
                                    fr.spotifeur.SearchSource.Spotify -> R.string.search_source_spotify
                                    fr.spotifeur.SearchSource.YouTubeMusic -> R.string.search_source_youtube_short
                                    fr.spotifeur.SearchSource.Local -> R.string.search_source_device
                                }), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        SearchFilterType.entries.forEach { type ->
                            SearchFilterChip(type, filter, { filter = it })
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when {
                            discovery.error != null && results.isEmpty() -> Text(
                                discovery.error.orEmpty(),
                                Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                            results.isEmpty() && discovery.loading -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
                                ContainedLoadingIndicator()
                            }
                            results.isEmpty() -> EmptySearchResults(searchQuery = searchQuery, colorScheme = colorScheme)
                            else -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // "All": one list, most relevant first, kinds and sources mixed, as Spotify's own search.
                                // A kind filter: that kind only, in the same order.
                                items(results, key = { "${it.kind}-${it.id}" }, contentType = { it.kind }) { item ->
                                    when (item.kind) {
                                        MusicKind.Track -> {
                                            val song = item.toSong(item.id in saved)
                                            val current = stable.currentSong?.id == song.id
                                            EnhancedSongListItem(
                                                song = song,
                                                isPlaying = current && stable.isPlaying,
                                                isCurrentSong = current,
                                                onMoreOptionsClick = { selected = it },
                                                onLongPress = { selected = song },
                                                onClick = {
                                                    ui.rememberSearchResult(item)
                                                    ui.playSongs(songs, song, searchQuery)
                                                },
                                                showSourceBadge = true,
                                            )
                                        }
                                        MusicKind.Album -> SearchResultAlbumItem(
                                            album = item.toAlbum(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                        MusicKind.Artist -> SearchResultArtistItem(
                                            artist = item.toArtist(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                        else -> SearchResultPlaylistItem(
                                            playlist = item.toPlaylist(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                    }
                                }
                            }
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
    SongOptionsHost(ui, navController, selected, songs, searchQuery) { selected = null }
    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
}
