package fr.spotifeur.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.ConversionMatching
import fr.spotifeur.data.ConversionRow
import fr.spotifeur.data.MatchState
import fr.spotifeur.data.Music
import fr.spotifeur.data.PlaylistConversion
import fr.spotifeur.data.PlaylistKind
import fr.spotifeur.data.source
import fr.spotifeur.presentation.components.SmartImage
import fr.spotifeur.presentation.components.SmartImageCompactListTargetSize
import fr.spotifeur.presentation.components.SourceBadge
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.utils.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun playlistKindName(kind: PlaylistKind): String = stringResource(when (kind) {
    PlaylistKind.Spotify -> R.string.search_source_spotify
    PlaylistKind.YouTubeMusic -> R.string.search_source_youtube
    PlaylistKind.MultiService -> R.string.badge_local
})

@Composable
private fun PlaylistKindIcon(kind: PlaylistKind, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) = when (kind) {
    PlaylistKind.Spotify -> Icon(painterResource(R.drawable.ic_source_spotify), null, modifier, tint = tint)
    PlaylistKind.YouTubeMusic -> Icon(painterResource(R.drawable.ic_source_youtube_music), null, modifier, tint = tint)
    PlaylistKind.MultiService -> Icon(Icons.Rounded.LibraryMusic, null, modifier, tint = tint)
}

/** "Convert to…": the destinations a playlist can go to, with the account each one needs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConvertTargetSheet(model: SpotifeurModel, item: Music, onDismiss: () -> Unit, onPick: (PlaylistKind) -> Unit) {
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val targets = remember(item.id, google, discovery.spotifyEnabled) { model.conversionTargets(item) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.convert_to), Modifier.padding(start = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            targets.forEach { (kind, available) ->
                Row(
                    Modifier.fillMaxWidth().clip18().clickable(enabled = available) { onPick(kind) }.padding(12.dp).alpha(if (available) 1f else 0.5f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistKindIcon(kind, Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(playlistKindName(kind), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when {
                            kind == PlaylistKind.MultiService -> R.string.convert_target_multi
                            !available && kind == PlaylistKind.Spotify -> R.string.convert_needs_spotify
                            !available -> R.string.convert_needs_google
                            kind == PlaylistKind.Spotify -> R.string.convert_target_spotify
                            else -> R.string.convert_target_youtube
                        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

private fun Modifier.clip18() = clip(RoundedCornerShape(18.dp))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConvertScreen(model: SpotifeurModel, navController: NavHostController, playlistId: String, targetKey: String) {
    val ui = model.ui
    val source = remember(playlistId) { ui.entity(playlistId) }
    val target = remember(targetKey) { PlaylistKind.fromKey(targetKey) }
    if (source == null || target == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    LaunchedEffect(playlistId, target) { model.startConversion(source, target) }
    val conversion by model.conversion.collectAsStateWithLifecycle()
    val session = conversion?.takeIf { it.source.id == playlistId && it.target == target }
    fun leave() { model.endConversion(); navController.popBackStack() }
    BackHandler { leave() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.convert_title), fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(horizontal = 6.dp).size(14.dp))
                            PlaylistKindIcon(target, Modifier.size(14.dp))
                            Text(playlistKindName(target), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_cancel)) } },
            )
        },
    ) { inner ->
        if (session == null) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        } else ConversionContent(model, navController, session, inner)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversionContent(model: SpotifeurModel, navController: NavHostController, session: PlaylistConversion, inner: PaddingValues) {
    val state by session.state.collectAsState()
    val createFailed = stringResource(R.string.convert_failed)
    val youTubeRefused = stringResource(R.string.youtube_refused)
    val createdMessage = stringResource(R.string.convert_done)
    var onlyReview by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.created) {
        val created = state.created ?: return@LaunchedEffect
        model.showMessage(createdMessage.format(created.title))
        model.endConversion()
        navController.popBackStack()
        navController.openMusic(model.ui, created)
    }
    LaunchedEffect(state.toReview) { if (state.toReview == 0) onlyReview = false }
    val rows = if (onlyReview) state.rows.filter { it.needsReview } else state.rows
    // The mini player floats over the bottom of the screen while something is loaded.
    val hasSong by model.ui.derivedHasSong.collectAsStateWithLifecycle()
    Column(Modifier.padding(inner).fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(state.name, session::rename, Modifier.fillMaxWidth(), singleLine = true, enabled = !state.creating,
                        label = { Text(stringResource(R.string.playlist_name)) })
                    when {
                        state.reading -> {
                            Text(stringResource(R.string.convert_reading), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        state.loadError != null -> Text(stringResource(R.string.convert_read_failed), color = MaterialTheme.colorScheme.error)
                        !state.finished -> {
                            Text(stringResource(R.string.convert_searching, state.searched, state.rows.size), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { if (state.rows.isEmpty()) 0f else state.searched.toFloat() / state.rows.size },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> Text(stringResource(R.string.convert_summary, state.tracks.size, state.rows.size), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.toReview > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = onlyReview, onClick = { onlyReview = !onlyReview },
                            label = { Text(stringResource(R.string.convert_to_review, state.toReview)) })
                    }
                }
            }
            items(rows, key = { it.position }) { row ->
                ConversionRowItem(row, enabled = !state.creating, onOpen = { picking = row.position },
                    onRemove = { session.remove(row.position) }, onRestore = { session.restore(row.position) })
            }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()
                .padding(bottom = if (hasSong) fr.spotifeur.presentation.components.MiniPlayerHeight else 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = session::dismissError) { Text(stringResource(R.string.action_ok)) }
                    }
                }
                if (state.creating) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        session.create(
                            creator = { name, tracks -> model.createConverted(session.target, name, tracks) },
                            failed = { failure ->
                                val status = (failure as? fr.spotifeur.data.youtube.YouTubeFailure)?.status
                                if (status == "403" || status == "429") youTubeRefused
                                else failure.message?.takeIf(String::isNotBlank) ?: createFailed
                            },
                        )
                    },
                    enabled = state.finished && state.tracks.isNotEmpty() && !state.creating && state.name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.convert_create, fr.spotifeur.utils.formatSongCount(state.tracks.size)))
                }
            }
        }
    }
    picking?.let { position ->
        state.rows.firstOrNull { it.position == position }?.let { row ->
            MatchPickerSheet(model, session, row) { picking = null }
        }
    }
}

@Composable
private fun ConversionRowItem(row: ConversionRow, enabled: Boolean, onOpen: () -> Unit, onRemove: () -> Unit, onRestore: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        row.removed -> colors.surfaceContainerLow
        row.needsReview && row.choice == null -> colors.errorContainer
        row.needsReview -> colors.tertiaryContainer
        else -> colors.surfaceContainer
    }
    val content = when {
        row.removed -> colors.onSurfaceVariant
        row.needsReview && row.choice == null -> colors.onErrorContainer
        row.needsReview -> colors.onTertiaryContainer
        else -> colors.onSurface
    }
    val strike = if (row.removed) TextDecoration.LineThrough else null
    Surface(
        color = background, contentColor = content, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().alpha(if (row.removed) 0.6f else 1f),
        onClick = onOpen, enabled = enabled && row.state != MatchState.Searching,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.state != MatchState.Same) Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(row.original.source, Modifier.padding(end = 6.dp))
                    Text("${row.original.title} · ${row.original.subtitle}", style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = strike, color = content.copy(alpha = 0.75f))
                }
                val choice = row.choice
                when {
                    row.state == MatchState.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.convert_row_searching), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    choice != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        SmartImage(model = choice.imageUrl, contentDescription = null, shape = RoundedCornerShape(10.dp),
                            targetSize = SmartImageCompactListTargetSize, modifier = Modifier.size(42.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(choice.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = strike)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceBadge(choice.source, Modifier.padding(end = 6.dp))
                                Text(choice.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (row.reviewed && !row.removed) Icon(Icons.Rounded.Check, null, Modifier.padding(start = 6.dp).size(18.dp))
                    }
                    else -> Text(stringResource(if (row.failed) R.string.convert_row_failed else R.string.convert_row_missing),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                if (row.needsReview) Text(stringResource(if (row.choice == null) R.string.convert_row_choose else R.string.convert_row_check),
                    style = MaterialTheme.typography.labelMedium)
            }
            if (row.state != MatchState.Searching) {
                if (row.removed) IconButton(onClick = onRestore, enabled = enabled) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.convert_restore)) }
                else IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Rounded.Close, stringResource(R.string.convert_remove)) }
            }
        }
    }
}

/** The matches found for a track, a search of the destination, and keeping or leaving out the track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchPickerSheet(model: SpotifeurModel, session: PlaylistConversion, row: ConversionRow, onDismiss: () -> Unit) {
    val initial = remember(row.position) { ConversionMatching.searchText(row.original) }
    var query by remember(row.position) { mutableStateOf(initial) }
    var results by remember(row.position) { mutableStateOf<List<Music>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val searchSource = when (session.target) {
        PlaylistKind.Spotify -> SpotifeurModel.TRACKS_SPOTIFY
        PlaylistKind.YouTubeMusic -> SpotifeurModel.TRACKS_YOUTUBE
        PlaylistKind.MultiService -> null
    }
    LaunchedEffect(query) {
        if (searchSource == null || query == initial || query.trim().length < 2) { results = null; loading = false; return@LaunchedEffect }
        delay(350)
        loading = true
        try { results = model.searchTracks(query, searchSource) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { results = emptyList() }
        finally { loading = false }
    }
    // Lazy list keys must be unique: a search can return the same track twice.
    val shown = (results ?: row.candidates).distinctBy(Music::id)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.padding(start = 8.dp)) {
                Text(row.original.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(row.original.source, Modifier.padding(end = 6.dp))
                    Text(listOfNotNull(row.original.subtitle.takeIf(String::isNotBlank), row.original.album.takeIf(String::isNotBlank),
                        row.original.durationMs.takeIf { it > 0 }?.let { formatDuration(it.toLong()) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (searchSource != null) OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(28.dp),
                label = { Text(stringResource(R.string.convert_search_on, playlistKindName(session.target))) },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (shown.isEmpty() && !loading && searchSource != null) item {
                    Text(stringResource(R.string.convert_no_candidates), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { it.id }) { candidate ->
                    Row(
                        Modifier.fillMaxWidth().clip18().clickable { session.choose(row.position, candidate); onDismiss() }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = candidate.id == row.choice?.id, onClick = { session.choose(row.position, candidate); onDismiss() })
                        SmartImage(model = candidate.imageUrl, contentDescription = null, shape = RoundedCornerShape(10.dp),
                            targetSize = SmartImageCompactListTargetSize, modifier = Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(candidate.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceBadge(candidate.source, Modifier.padding(end = 6.dp))
                                Text(listOfNotNull(candidate.subtitle.takeIf(String::isNotBlank),
                                    candidate.durationMs.takeIf { it > 0 }?.let { formatDuration(it.toLong()) }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { model.play(listOf(candidate), 0) }) {
                            Icon(Icons.Rounded.PlayArrow, stringResource(R.string.convert_listen))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (row.removed) OutlinedButton(onClick = { session.restore(row.position); onDismiss() }) { Text(stringResource(R.string.convert_restore)) }
                else OutlinedButton(onClick = { session.remove(row.position); onDismiss() }) { Text(stringResource(R.string.convert_remove)) }
                if (row.needsReview && row.choice != null) Button(onClick = { session.confirm(row.position); onDismiss() }) {
                    Text(stringResource(R.string.convert_keep_match))
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
