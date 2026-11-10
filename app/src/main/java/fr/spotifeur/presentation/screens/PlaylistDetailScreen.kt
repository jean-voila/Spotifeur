package fr.spotifeur.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SwapHoriz
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.data.preferences.DetailAction
import fr.spotifeur.data.preferences.DetailPage
import fr.spotifeur.presentation.utils.shareMusic
import fr.spotifeur.presentation.utils.spotifyShareUrl
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.LOCAL_PLAYLIST_PREFIX
import fr.spotifeur.presentation.components.PlaylistNameDialog
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toSong
import fr.spotifeur.presentation.components.PlaylistSortBottomSheet
import fr.spotifeur.presentation.viewmodel.PlaylistSort
import fr.spotifeur.presentation.viewmodel.sortedForPlaylist
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.components.subcomps.EnhancedSongListItem
import fr.spotifeur.presentation.navigation.openMusic
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import fr.spotifeur.data.Music
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.presentation.components.SpotifyPlaylistEditor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaylistDetailScreen(model: SpotifeurModel, navController: NavHostController, playlistId: String) {
    val ui = model.ui
    val context = LocalContext.current
    val item = remember(playlistId) { ui.entity(playlistId) }
    if (item == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val details by model.details.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    LaunchedEffect(playlistId) { model.loadDetail(item) }
    val detail = details[playlistId]
    val content = detail?.content
    val current = content?.item ?: item
    var sort by remember(playlistId) { mutableStateOf(ui.playlistSort(playlistId)) }
    var sortMenu by remember { mutableStateOf(false) }
    val localFiles by model.localTracks.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val exportCsv = fr.spotifeur.presentation.components.rememberPlaylistCsvExport(model)
    // Positions in the stored playlist, so removing a track still targets the right one once sorted.
    val ordered = remember(content?.tracks, saved, sort, localFiles) {
        model.withLocalFiles(content?.tracks.orEmpty()).mapIndexed { index, music -> index to music.toSong(music.id in saved) }
            .sortedForPlaylist(sort) { it.second.title }
    }
    // Search inside this playlist, revealed by pulling down at the top of the list.
    var filter by remember(playlistId) { mutableStateOf("") }
    val visible = remember(ordered, filter) { ordered.filter { it.second.matchesPlaylistFilter(filter) } }
    val songs = remember(visible) { visible.map { it.second } }
    val isPinned = pins.any { it.id == playlistId }
    val isLiked = playlistId == ui.likedSongs.id
    val isLocalList = playlistId.startsWith(LOCAL_PLAYLIST_PREFIX)
    val isYouTubeList = playlistId.startsWith(fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX)
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val youTubeEditable by model.youTubeEditable.collectAsStateWithLifecycle()
    val youTubePlaylists by model.youTubePlaylists.collectAsStateWithLifecycle()
    // The Google account's own playlist: its page showed the editing header, or the library names the account as its owner.
    val ownsYouTube = isYouTubeList && google != null && (playlistId in youTubeEditable || model.youTubePlaylistOwned(current))
    val inYouTubeLibrary = isYouTubeList && google != null && youTubePlaylists.any { it.id == playlistId }
    val playlistKind = fr.spotifeur.data.playlistKindOf(current)
    val library by model.library.collectAsStateWithLifecycle()
    val inSpotifyLibrary = discovery.spotifyEnabled && library.content?.playlists?.any { it.id == playlistId } == true
    val scope = rememberCoroutineScope()
    val online by model.online.collectAsStateWithLifecycle()
    var isOwned by remember(playlistId) { mutableStateOf(false) }
    var suggestions by remember(playlistId) { mutableStateOf<List<Music>>(emptyList()) }
    var suggestionsLoading by remember { mutableStateOf(false) }
    var suggestionError by remember { mutableStateOf<String?>(null) }
    var addingSuggestion by remember { mutableStateOf<String?>(null) }
    var suggestionRevision by remember { mutableStateOf(0) }
    LaunchedEffect(playlistId, current.ownerUsername, online) {
        if (!isLocalList && !isYouTubeList && !isLiked && online) {
            isOwned = runCatching {
                current.ownerUsername?.let { it == SpotifyAccount.require(context).username() } ?: model.spotifyPlaylists.isOwned(playlistId)
            }.getOrDefault(false)
        }
    }
    LaunchedEffect(isOwned, content?.tracks, online, suggestionRevision) {
        if (!isOwned || !online || content == null) return@LaunchedEffect
        suggestionsLoading = true; suggestionError = null
        try { suggestions = model.playlistSuggestions(current, content.tracks).let { list ->
            val offset = if (list.isEmpty()) 0 else (suggestionRevision * 5) % list.size
            list.drop(offset) + list.take(offset)
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { suggestionError = failure.message }
        finally { suggestionsLoading = false }
    }
    var renaming by remember { mutableStateOf(false) }
    var reordering by remember(playlistId) { mutableStateOf(false) }
    var addingTracks by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var selected by remember { mutableStateOf<Song?>(null) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = PLAYLIST_FILTER_ITEMS)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Stable identities let a track appearing twice be dragged without the list jumping.
    val rowKeys = remember(songs) {
        val seen = HashMap<String, Int>()
        songs.map { song -> val occurrence = seen.merge(song.id, 1, Int::plus) ?: 1; "${song.id}#$occurrence" }
    }
    var removing by remember(playlistId) { mutableStateOf(false) }
    // Removes the row shown at a position of the displayed (possibly sorted) list.
    val removeAt: ((Int) -> Unit)? = when {
        isLocalList -> { index -> visible.getOrNull(index)?.let { model.localPlaylistStore.removeAt(playlistId, it.first) } }
        (isOwned && !isLiked || ownsYouTube) && online -> { index ->
            visible.getOrNull(index)?.let { (position, _) ->
                if (!removing) {
                    removing = true
                    scope.launch {
                        try {
                            if (isYouTubeList) model.removeYouTubePlaylistTrack(current, position)
                            else model.removeSpotifyPlaylistTrack(current, position)
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { model.showMessage(failure.message.orEmpty()) }
                        finally { removing = false }
                    }
                }
            }
        }
        else -> null
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val first = PLAYLIST_FILTER_ITEMS + 1
        if (from.index >= first && to.index >= first) model.localPlaylistStore.move(playlistId, from.index - first, to.index - first)
    }

    // Another order only means something once the whole playlist is there.
    LaunchedEffect(sort, filter.isBlank(), content?.nextPage, detail?.loading) {
        if ((sort != PlaylistSort.DEFAULT || filter.isNotBlank()) && content?.nextPage != null && detail?.loading != true) model.loadDetail(item, more = true)
    }
    LaunchedEffect(sort) { listState.scrollToItem(if (filter.isBlank()) PLAYLIST_FILTER_ITEMS else 0) }
    // A list shorter than the screen cannot start below the search field: hide it once the tracks are there.
    LaunchedEffect(ordered.isNotEmpty()) {
        if (ordered.isNotEmpty() && filter.isBlank() && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
            listState.scrollToItem(PLAYLIST_FILTER_ITEMS)
        }
    }

    LaunchedEffect(listState, songs.size, content?.nextPage) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (content?.nextPage != null && last >= songs.size - 12) model.loadDetail(item, more = true) }
    }

    // Multi-service and YouTube Music pages carry the track count as their subtitle; the header adds it already.
    val sourceName = when {
        isLocalList -> stringResource(R.string.badge_local)
        isYouTubeList -> stringResource(R.string.search_source_youtube)
        else -> null
    }
    CollectionDetailLayout(
        current.copy(subtitle = current.ownerUsername ?: sourceName ?: current.subtitle),
        content?.total ?: ordered.size, ui, navController, listState, hiddenLeadingItems = PLAYLIST_FILTER_ITEMS,
    ) {
        item(key = "playlist_filter") { PlaylistFilterField(filter, enabled = !reordering) { filter = it } }
        item(key = "playlist_actions") {
            val shareContext = LocalContext.current
            val editable = isLocalList || isOwned || ownsYouTube
            val actions = buildList {
                add(downloadAction(ui, current))
                if (editable) add(ActionSpec(DetailAction.ADD_TRACKS, Icons.Rounded.Add, stringResource(R.string.playlist_add_tracks),
                    { addingTracks = true }, enabled = isLocalList || online))
                if (!isLocalList && !isYouTubeList && !isLiked && discovery.spotifyEnabled) add(ActionSpec(DetailAction.SAVE,
                    if (playlistId in saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (playlistId in saved) R.string.saved_remove else R.string.saved_add), { model.toggleFavorite(current) },
                    selected = playlistId in saved))
                add(ActionSpec(DetailAction.PIN, if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                    stringResource(if (isPinned) R.string.pin_remove else R.string.pin_add_home), { ui.togglePin(current) }, selected = isPinned))
                add(ActionSpec(DetailAction.SORT, Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.playlist_sort), { sortMenu = true }))
                if (isLocalList) add(ActionSpec(DetailAction.REORDER, Icons.Rounded.SwapVert, stringResource(R.string.reorder),
                    { reordering = !reordering; if (reordering) filter = ""; if (reordering && sort != PlaylistSort.DEFAULT) { sort = PlaylistSort.DEFAULT; ui.setPlaylistSort(playlistId, sort) } },
                    selected = reordering))
                if (editable) add(ActionSpec(DetailAction.EDIT, Icons.Rounded.Edit, stringResource(R.string.detail_edit), { renaming = true },
                    enabled = isLocalList || online))
                if (!isLocalList && !isYouTubeList && discovery.spotifyEnabled) add(ActionSpec(DetailAction.RADIO, Icons.Rounded.Radio, stringResource(R.string.radio_related),
                    { ui.startRadio(current) { navController.openMusic(ui, it) } }))
                if (current.spotifyShareUrl() != null) add(ActionSpec(DetailAction.SHARE, Icons.Rounded.Share, stringResource(R.string.detail_share),
                    { shareContext.shareMusic(current) }))
                add(ActionSpec(DetailAction.EXPORT, Icons.Rounded.TableChart, stringResource(R.string.playlist_export_csv), { exportCsv(current) },
                    enabled = content != null))
                if (playlistKind != null) add(ActionSpec(DetailAction.CONVERT, Icons.Rounded.SwapHoriz, stringResource(R.string.convert_to),
                    { converting = true }, enabled = content != null && !content.tracks.isEmpty()))
                if (isLocalList || (inSpotifyLibrary && !isLiked) || inYouTubeLibrary) add(ActionSpec(DetailAction.DELETE, Icons.Rounded.Delete,
                    stringResource(R.string.playlist_delete), { deleting = true }, destructive = true))
            }
            DetailActionBar(DetailPage.PLAYLIST, actions, ui)
            if (reordering) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.playlist_reorder_hint), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { reordering = false }) { Text(stringResource(R.string.playlist_reorder_done)) }
            }
            fr.spotifeur.presentation.components.CollectionDownloadProgress(ui, current)
        }
        if (songs.isEmpty() && detail?.loading != false) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        }
        if (detail?.error != null) item { Text(detail.error, color = MaterialTheme.colorScheme.error) }
                    // A playlist whose count is known but whose tracks did not come is not empty: say so and offer a retry.
                    val unread = ordered.isEmpty() && (content?.total ?: 0) > 0 && detail?.loading == false
                    if (unread) item {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.playlist_tracks_unavailable))
                            TextButton(onClick = {
                                if (isLiked) model.refreshLibrary()
                                model.loadDetail(item, refresh = true)
                            }, enabled = online) { Text(stringResource(R.string.google_signin_retry)) }
                        }
                    } else if (ordered.isEmpty() && detail?.loading == false) item { Text(stringResource(R.string.playlist_empty_title), Modifier.padding(16.dp)) }
                    else if (songs.isEmpty() && filter.isNotBlank()) item {
                        Text(stringResource(if (content?.nextPage != null) R.string.playlist_filter_loading else R.string.playlist_filter_empty), Modifier.padding(16.dp))
                    }
                    itemsIndexed(songs, key = { index, song -> rowKeys.getOrElse(index) { "$index-${song.id}" } }, contentType = { _, _ -> "playlist_song" }) { index, song ->
                        val isCurrent = stable.currentSong?.id == song.id
                        val row: @Composable (Modifier) -> Unit = { modifier ->
                            EnhancedSongListItem(
                                modifier = modifier,
                                song = song,
                                isPlaying = isCurrent && stable.isPlaying,
                                isCurrentSong = isCurrent,
                                onMoreOptionsClick = { selected = it; selectedIndex = index },
                                onLongPress = { selected = song; selectedIndex = index },
                                onClick = { ui.playSongs(songs, song, current.title) },
                                showSourceBadge = isLocalList,
                                onSwipeRemove = removeAt?.let { remove -> { remove(index) } },
                                swipeEnabled = !reordering,
                            )
                        }
                        // Drag handles only appear in reorder mode, chosen from the page's buttons.
                        if (reordering && isLocalList && sort == PlaylistSort.DEFAULT && filter.isBlank()) {
                            ReorderableItem(reorderState, key = rowKeys[index]) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                        Icon(Icons.Rounded.DragHandle, stringResource(R.string.reorder))
                                    }
                                    row(Modifier.weight(1f))
                                }
                            }
                        } else row(Modifier)
                    }
                    if (isOwned) {
                        item(key = "recommendation_heading") {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.spotify_playlist_suggestions), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.spotify_playlist_suggestions_description), style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { suggestionRevision++ }, enabled = online && !suggestionsLoading) { Text(stringResource(R.string.spotify_playlist_refresh)) }
                            }
                        }
                        if (suggestionsLoading) item { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp)) }
                        if (suggestionError != null) item { Text(suggestionError.orEmpty(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                        itemsIndexed(suggestions.take(5), key = { _, track -> "suggestion:${track.id}" }) { _, track ->
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                coil.compose.AsyncImage(track.imageUrl, null, Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(enabled = online && addingSuggestion == null, onClick = {
                                    addingSuggestion = track.id
                                    scope.launch {
                                        try {
                                            model.addSpotifyPlaylistTracks(current, listOf(track))
                                            suggestions = suggestions.filterNot { it.id == track.id }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { model.showMessage(failure.message.orEmpty()) }
                                        finally { addingSuggestion = null }
                                    }
                                }) { Icon(Icons.Rounded.Add, stringResource(R.string.spotify_playlist_add_suggestion)) }
                            }
                        }
                    }
    }
    if (sortMenu) PlaylistSortBottomSheet(
        options = listOf(
            PlaylistSort.DEFAULT to R.string.sort_display_default_order,
            PlaylistSort.REVERSED to R.string.playlist_sort_reversed,
            PlaylistSort.TITLE to R.string.sort_display_title_az,
        ),
        selected = sort,
        onDismiss = { sortMenu = false },
    ) { option ->
        sortMenu = false
        sort = option
        ui.setPlaylistSort(playlistId, option)
    }
    SongOptionsHost(
        ui, navController, selected, songs, current.title,
        onRemoveFromPlaylist = removeAt?.let { remove -> { remove(selectedIndex) } },
    ) { selected = null }
    if (renaming && isOwned) SpotifyPlaylistEditor(model, playlist = current, onDismiss = { renaming = false })
    if (addingTracks) fr.spotifeur.presentation.components.AddTracksSheet(model, current,
        target = playlistKind ?: fr.spotifeur.data.PlaylistKind.MultiService,
        onAddLocal = { tracks -> model.localPlaylistStore.add(playlistId, tracks) },
        onAddRemote = { tracks ->
            if (isYouTubeList) model.addYouTubePlaylistTracks(current, tracks) else model.addSpotifyPlaylistTracks(current, tracks)
        },
        onDismiss = { addingTracks = false })
    if (renaming && ownsYouTube) fr.spotifeur.presentation.components.YouTubePlaylistEditor(model, current) { renaming = false }
    if (converting) ConvertTargetSheet(model, current, onDismiss = { converting = false }) { kind ->
        converting = false
        ui.register(current)
        navController.navigateSafely(fr.spotifeur.presentation.navigation.Screen.Convert.createRoute(playlistId, kind.key))
    }
    if (renaming && isLocalList) fr.spotifeur.presentation.components.LocalPlaylistEditor(model, current) { renaming = false }
    if (deleting) fr.spotifeur.presentation.components.DeletePlaylistDialog(model, current, onDismiss = { deleting = false }) {
        navController.popBackStack()
    }
}

/** The search field item sits above the page's first visible item. */
private const val PLAYLIST_FILTER_ITEMS = 1

/** Title, artists or album contain every word of [query], ignoring case and accents. */
internal fun Song.matchesPlaylistFilter(query: String): Boolean = fr.spotifeur.utils.matchesWords(query, title, displayArtist, album)

@Composable
private fun PlaylistFilterField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.playlist_filter_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({
            IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.playlist_filter_clear)) }
        }) else null,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
    )
}
