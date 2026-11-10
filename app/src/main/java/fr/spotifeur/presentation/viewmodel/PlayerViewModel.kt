package fr.spotifeur.presentation.viewmodel

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.core.content.edit
import androidx.media3.common.Player
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.BackupManager
import fr.spotifeur.data.Music
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.data.source
import fr.spotifeur.playback.AudioCache
import fr.spotifeur.playback.CachedAudio
import fr.spotifeur.playback.DownloadCenter
import fr.spotifeur.playback.DownloadedTrack
import fr.spotifeur.utils.AppLocales
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.model.Artist
import fr.spotifeur.data.model.Lyrics
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.spotifyLongId
import fr.spotifeur.data.model.toArtist
import fr.spotifeur.data.model.toMusic
import fr.spotifeur.data.model.toSong
import fr.spotifeur.data.preferences.AlbumArtPaletteStyle
import fr.spotifeur.data.preferences.AppSettings
import fr.spotifeur.data.preferences.SettingsStore
import fr.spotifeur.data.preferences.AlbumArtQuality
import fr.spotifeur.data.preferences.CarouselStyle
import fr.spotifeur.data.preferences.FullPlayerLoadingTweaks
import fr.spotifeur.data.preferences.NavBarStyle
import fr.spotifeur.data.preferences.ThemePreference
import fr.spotifeur.data.repository.LyricsRepository
import fr.spotifeur.data.repository.LyricsSearchResult
import fr.spotifeur.utils.ValidatedLyricsImport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * The surface the composables expect from their PlayerViewModel, implemented on top of
 * Spotifeur's Spotify session and Media3 player. It owns no audio, network or credential logic.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Stable
class PlayerViewModel(val model: SpotifeurModel, private val scope: CoroutineScope) {
    private val context: Context get() = model.getApplication()
    private val colorProcessor by lazy { ColorSchemeProcessor(context) }
    private val uiPrefs = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
    /** Track order chosen for one playlist, kept on this device. */
    fun playlistSort(id: String): PlaylistSort =
        PlaylistSort.entries.firstOrNull { it.name == uiPrefs.getString(KEY_PLAYLIST_SORT + id, null) } ?: PlaylistSort.DEFAULT
    fun setPlaylistSort(id: String, sort: PlaylistSort) = uiPrefs.edit {
        if (sort == PlaylistSort.DEFAULT) remove(KEY_PLAYLIST_SORT + id) else putString(KEY_PLAYLIST_SORT + id, sort.name)
    }
    private val entities = ConcurrentHashMap<Long, Music>()

    /** Resolves the Long id the navigation carries back to its Spotify entity. */
    fun register(item: Music): Long = spotifyLongId(item.id).also { entities[it] = item }
    fun entity(id: Long): Music? = entities[id]
    fun entity(uri: String): Music? = entities[spotifyLongId(uri)]
    val likedSongs by lazy { Music(SpotifeurModel.LIKED_SONGS, context.getString(R.string.liked_title), context.getString(R.string.liked_subtitle), MusicKind.Playlist) }

    val cast = CastController(model) { sendToast(it) }
    val castRoutes get() = cast.castRoutes
    val selectedRoute get() = cast.selectedRoute
    val isRefreshingRoutes get() = cast.isRefreshingRoutes
    val isCastConnecting get() = cast.isCastConnecting
    val isRemotePlaybackActive get() = cast.isRemotePlaybackActive
    val routeVolume get() = cast.routeVolume
    val isWifiEnabled get() = cast.isWifiEnabled
    val isWifiRadioOn get() = cast.isWifiRadioOn
    val wifiName get() = cast.wifiName
    val isBluetoothEnabled get() = cast.isBluetoothEnabled
    val bluetoothName get() = cast.bluetoothName
    val bluetoothAudioDeviceStates get() = cast.bluetoothAudioDeviceStates
    fun refreshCastRoutes() = cast.refreshCastRoutes()
    fun refreshLocalConnectionInfo(@Suppress("UNUSED_PARAMETER") refreshBluetoothDevices: Boolean = false) = cast.refreshConnectivity()
    fun selectRoute(route: androidx.mediarouter.media.MediaRouter.RouteInfo) = cast.selectRoute(route)
    fun disconnect() = cast.disconnect()
    fun setRouteVolume(volume: Int) = cast.setRouteVolume(volume)
    private val mutableTrackVolume = MutableStateFlow(1f)
    val trackVolume: StateFlow<Float> = mutableTrackVolume.asStateFlow()
    fun setTrackVolume(volume: Float) {
        mutableTrackVolume.value = volume
        model.player.setVolume(volume)
    }

    private val mutableQueueName = MutableStateFlow("")
    private val mutableUi = MutableStateFlow(PlayerUiState())

    private val playbackSongs = model.player.state.map { it.queue }.distinctUntilChanged()

    val queueFlow: StateFlow<ImmutableList<Song>> = combine(playbackSongs, model.favorites) { songs, saved ->
        songs.map { it.toSong(it.id in saved) }.toImmutableList()
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, persistentListOf())

    private data class LyricsState(val songId: String? = null, val loading: Boolean = false, val lyrics: Lyrics? = null, val offset: Int = 0)
    private val mutableLyrics = MutableStateFlow(LyricsState())

    val stablePlayerState: StateFlow<StablePlayerState> =
        combine(model.player.state, model.favorites, mutableLyrics, cast.remotePlayback) { state, saved, lyrics, remote ->
            val remoteTrack = remote?.let { state.queue.filter(Music::isLocalTrack).getOrNull(it.index) }
            val track = remoteTrack ?: state.track
            val current = lyrics.takeIf { it.songId == track?.id }
            StablePlayerState(
                lyrics = current?.lyrics,
                isLoadingLyrics = current?.loading == true,
                currentSong = track?.let { it.toSong(it.id in saved) },
                currentMediaItemIndex = if (remoteTrack != null) state.queue.indexOfFirst { it.id == remoteTrack.id } else state.index,
                isPlaying = remote?.isPlaying ?: state.playing,
                playWhenReady = state.playWhenReady,
                totalDuration = remote?.durationMs ?: state.durationMs.toLong(),
                isShuffleEnabled = state.shuffle,
                repeatMode = state.repeat,
                isBuffering = state.buffering,
            )
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, StablePlayerState())

    val derivedHasSong: StateFlow<Boolean> = stablePlayerState.map { it.currentSong != null }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val currentPlaybackPosition: StateFlow<Long> = combine(model.player.state, cast.remotePlayback) { state, remote ->
        remote?.positionMs ?: state.positionMs.toLong()
    }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    val isCurrentSongFavorite: StateFlow<Boolean> = stablePlayerState
        .map { it.currentSong?.isFavorite == true }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val playerUiState: StateFlow<PlayerUiState> =
        combine(mutableUi, mutableQueueName, queueFlow) { ui, name, queue ->
            ui.copy(currentQueueSourceName = name, currentPlaybackQueue = queue)
        }.stateIn(scope, SharingStarted.Eagerly, PlayerUiState())

    // Sheet state -------------------------------------------------------------------------------
    private val mutableSheetState = MutableStateFlow(PlayerSheetState.COLLAPSED)
    val sheetState: StateFlow<PlayerSheetState> = mutableSheetState.asStateFlow()
    val playerContentExpansionFraction = Animatable(0f)
    private val mutablePredictiveBack = MutableStateFlow(0f)
    val predictiveBackCollapseFraction: StateFlow<Float> = mutablePredictiveBack.asStateFlow()
    private val mutableSwipeEdge = MutableStateFlow<Int?>(null)
    val predictiveBackSwipeEdge: StateFlow<Int?> = mutableSwipeEdge.asStateFlow()
    private val mutableMiniDismissing = MutableStateFlow(false)
    val isMiniPlayerDismissing: StateFlow<Boolean> = mutableMiniDismissing.asStateFlow()

    fun expandPlayerSheet() { mutableSheetState.value = PlayerSheetState.EXPANDED }
    fun collapsePlayerSheet() { mutableSheetState.value = PlayerSheetState.COLLAPSED }
    fun togglePlayerSheetState() {
        mutableSheetState.update { if (it == PlayerSheetState.COLLAPSED) PlayerSheetState.EXPANDED else PlayerSheetState.COLLAPSED }
    }
    fun updatePredictiveBackCollapseFraction(value: Float) { mutablePredictiveBack.value = value }
    fun updatePredictiveBackSwipeEdge(edge: Int?) { mutableSwipeEdge.value = edge }
    fun setMiniPlayerDismissing(value: Boolean) { mutableMiniDismissing.value = value }
    fun updateQueueSheetVisibility(@Suppress("UNUSED_PARAMETER") visible: Boolean) = Unit
    fun setSliderUiMounted(@Suppress("UNUSED_PARAMETER") mounted: Boolean) = Unit

    // Appearance --------------------------------------------------------------------------------
    data class PlayerConfigSlice(
        val navBarCornerRadius: Int = 32,
        val navBarStyle: String = NavBarStyle.DEFAULT,
        val carouselStyle: String = CarouselStyle.NO_PEEK,
        val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks = FullPlayerLoadingTweaks(),
        val tapBackgroundClosesPlayer: Boolean = false,
        val useSmoothCorners: Boolean = true,
        val playerThemePreference: String = ThemePreference.ALBUM_ART,
    )
    val settingsStore = SettingsStore.get(context)
    val settings: StateFlow<AppSettings> = settingsStore.settings
    fun updateSettings(change: (AppSettings) -> AppSettings) = settingsStore.update(change)
    val playerConfigSlice: StateFlow<PlayerConfigSlice> = settings.map { s ->
        PlayerConfigSlice(
            navBarCornerRadius = s.navBarCornerRadius,
            navBarStyle = s.navBarStyle,
            carouselStyle = s.carouselStyle,
            tapBackgroundClosesPlayer = s.tapBackgroundClosesPlayer,
            useSmoothCorners = s.useSmoothCorners,
            playerThemePreference = s.playerTheme,
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, PlayerConfigSlice())
    val navBarCompactMode: StateFlow<Boolean> = settings.map { it.navBarCompact }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, settings.value.navBarCompact)
    val disableBlurAllOver: StateFlow<Boolean> = settings.map { it.disableBlur }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, settings.value.disableBlur)

    private val artSchemes = ConcurrentHashMap<String, MutableStateFlow<ColorSchemePair?>>()
    private val artGate = Semaphore(2)

    /** Palette of an artwork, generated once and shared by every card that shows it. */
    fun getAlbumColorSchemeFlow(uri: String): StateFlow<ColorSchemePair?> {
        artSchemes[uri]?.let { return it }
        val flow = MutableStateFlow<ColorSchemePair?>(null)
        val existing = artSchemes.putIfAbsent(uri, flow)
        if (existing != null) return existing
        if (uri.isNotBlank()) scope.launch {
            flow.value = artGate.withPermit { colorProcessor.getOrGenerateColorScheme(uri, AlbumArtPaletteStyle.fromStorageKey(settings.value.palette)) }
        }
        return flow
    }

    private val mutableScheme = MutableStateFlow<ColorSchemePair?>(null)
    val activePlayerColorSchemePair: StateFlow<ColorSchemePair?> = mutableScheme.asStateFlow()
    private val mutableThemedArt = MutableStateFlow<String?>(null)
    val currentThemedAlbumArtUri: StateFlow<String?> = mutableThemedArt.asStateFlow()

    // Current track relations --------------------------------------------------------------------
    data class FullPlayerSlice(
        val currentSongArtists: List<Artist> = emptyList(),
        val currentSongAlbum: Music? = null,
        val albumArtQuality: AlbumArtQuality = AlbumArtQuality.HIGH,
        val audioMetaText: String? = null,
    )
    private val mutableSlice = MutableStateFlow(FullPlayerSlice())
    val fullPlayerSlice: StateFlow<FullPlayerSlice> =
        combine(mutableSlice, model.player.state.map { it.audioFormat }.distinctUntilChanged(), settings) { slice, format, s ->
            slice.copy(audioMetaText = format.takeIf { s.showPlayerFileInfo && it.isNotBlank() })
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, FullPlayerSlice())

    /** Formats measured while a Spotify track played: the only quality this app can state for it. */
    private val measuredQuality = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 4000
    })
    private val mutableQualityRevision = MutableStateFlow(0)
    val qualityRevision: StateFlow<Int> = mutableQualityRevision.asStateFlow()
    fun qualityOf(song: Song): String? = song.quality.takeIf { it.isNotBlank() } ?: measuredQuality[song.id]

    private val mutableArtistNavigation = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val artistNavigationRequests: SharedFlow<Long> = mutableArtistNavigation.asSharedFlow()
    private val mutableAlbumNavigation = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val albumNavigationRequests: SharedFlow<Long> = mutableAlbumNavigation.asSharedFlow()

    private suspend fun relations(song: Song): List<Music> = try {
        model.trackRelations(song.toMusic()).onEach(::register)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { emptyList() }

    fun triggerArtistNavigationFromPlayer(artistId: Long) { mutableArtistNavigation.tryEmit(artistId) }
    fun openAlbumOf(song: Song) { scope.launch {
        if (song.isLocal) { sendToast(context.getString(R.string.local_no_album)); return@launch }
        val album = relations(song).firstOrNull { it.kind == MusicKind.Album }
        if (album != null) mutableAlbumNavigation.tryEmit(spotifyLongId(album.id))
        else sendToast(context.getString(R.string.album_not_found))
    } }
    fun openArtistOf(song: Song) { scope.launch {
        if (song.isLocal) { sendToast(context.getString(R.string.local_no_artist)); return@launch }
        val artist = relations(song).firstOrNull { it.kind == MusicKind.Artist }
        if (artist != null) mutableArtistNavigation.tryEmit(spotifyLongId(artist.id))
        else sendToast(context.getString(R.string.artist_not_found))
    } }

    init {
        register(likedSongs)
        DownloadCenter.start(context)
        uiPrefs.getString(KEY_QUALITY, null)?.let { saved ->
            runCatching { val json = org.json.JSONObject(saved); json.keys().forEach { measuredQuality[it] = json.getString(it) } }
        }
        scope.launch {
            model.player.state.map { it.track?.id to it.audioFormat }.distinctUntilChanged().collect { (id, format) ->
                if (id != null && format.isNotBlank() && !fr.spotifeur.data.isLocalTrackId(id) && measuredQuality[id] != format) {
                    measuredQuality[id] = format
                    mutableQualityRevision.update { it + 1 }
                    uiPrefs.edit { putString(KEY_QUALITY, org.json.JSONObject(measuredQuality.toMap()).toString()) }
                }
            }
        }
        scope.launch {
            stablePlayerState.map { it.currentSong }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { song ->
                mutableSlice.value = FullPlayerSlice()
                val art = song?.albumArtUriString
                if (art != null) launch {
                    val pair = colorProcessor.getOrGenerateColorScheme(art, AlbumArtPaletteStyle.fromStorageKey(settings.value.palette))
                    if (pair != null && stablePlayerState.value.currentSong?.albumArtUriString == art) {
                        mutableScheme.value = pair
                        mutableThemedArt.value = art
                    }
                }
                if (song != null) launch {
                    val related = relations(song)
                    if (stablePlayerState.value.currentSong?.id == song.id) {
                        mutableSlice.update { slice -> slice.copy(
                            currentSongArtists = related.filter { it.kind == MusicKind.Artist }.map(Music::toArtist),
                            currentSongAlbum = related.firstOrNull { it.kind == MusicKind.Album },
                        ) }
                    }
                }
            }
        }
    }

    // Transport ---------------------------------------------------------------------------------
    private val casting: Boolean get() = cast.isRemotePlaybackActive.value
    fun playPause() = if (casting) cast.togglePlayback() else model.player.toggle()
    /** How many tracks the user queued after the current one, for the queue's sections. */
    val queuedCount: StateFlow<Int> = model.player.state.map { it.queuedCount }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 0)
    fun nextSong() = if (casting) cast.next() else model.player.next()
    fun previousSong() = if (casting) cast.previous() else model.player.previous()
    /** The previous track itself, whatever the position in the current one (mini player swipe). */
    fun previousTrack() = if (casting) cast.previous() else model.player.previousTrack()
    fun seekTo(position: Long) =
        if (casting) cast.seek(position) else model.player.seek(position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    fun toggleShuffle() = model.player.shuffle()
    fun cycleRepeatMode() = model.player.repeat()

    fun toggleFavorite() { stablePlayerState.value.currentSong?.let(::toggleFavoriteSpecificSong) }
    fun toggleFavoriteSpecificSong(song: Song) {
        if (song.isLocal) sendToast(context.getString(R.string.local_no_favorite))
        else model.toggleFavorite(song.toMusic())
    }

    /** Adds whatever [item] holds to a local playlist, which accepts Spotify and local tracks alike. */
    fun addToLocalPlaylist(playlistId: String, item: Music) = withTracks(item) { tracks ->
        model.localPlaylistStore.add(playlistId, tracks)
        sendToast(context.getString(R.string.playlist_added))
    }
    fun createLocalPlaylist(name: String, item: Music? = null) {
        val playlist = model.localPlaylistStore.create(name)
        if (item != null) addToLocalPlaylist(playlist.id, item)
    }

    fun playSongs(songsToPlay: List<Song>, startSong: Song, queueName: String = "") {
        val index = songsToPlay.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        mutableQueueName.value = queueName
        model.play(songsToPlay.map(Song::toMusic), index)
    }

    fun playSongsShuffled(songsToPlay: List<Song>, queueName: String = "") {
        if (songsToPlay.isEmpty()) return
        if (!model.player.state.value.shuffle) model.player.shuffle()
        playSongs(songsToPlay, songsToPlay.random(), queueName)
    }

    /** Opens the sheet content in place: tapping inside the live queue must not rebuild it. */
    fun showAndPlaySong(song: Song, contextSongs: List<Song>, queueName: String = "", indexInQueue: Int? = null) {
        val state = model.player.state.value
        val displayIndex = indexInQueue ?: contextSongs.indexOfFirst { it.id == song.id }
        // The live queue sheet shows the player's window: a tap in it moves within that window.
        if (displayIndex >= 0 && state.queue.size == contextSongs.size && state.queue.map(Music::id) == contextSongs.map(Song::id)) {
            model.player.seekToItem(displayIndex)
            return
        }
        mutableQueueName.value = queueName
        model.play(contextSongs.map(Song::toMusic), displayIndex.coerceAtLeast(0))
    }

    /** Random playback of a whole entity: album, artist or playlist of the library. */
    fun shuffleItem(item: Music) { scope.launch {
        val tracks = try { model.tracksOf(item) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        if (tracks.isEmpty()) sendToast(context.getString(R.string.no_tracks_for, item.title))
        else playSongsShuffled(tracks.map { it.toSong() }, item.title)
    } }

    private fun withTracks(item: Music, action: (List<Music>) -> Unit) { scope.launch {
        val tracks = try { model.tracksOf(item) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        if (tracks.isEmpty()) sendToast(context.getString(R.string.no_tracks_for, item.title))
        else action(tracks)
    } }

    fun playItem(item: Music) = withTracks(item) { tracks ->
        if (model.player.state.value.shuffle) model.player.shuffle()
        playSongs(tracks.map { it.toSong() }, tracks.first().toSong(), item.title)
    }
    fun addItemToQueue(item: Music) = withTracks(item) { tracks ->
        model.player.addToQueue(tracks)
        sendToast(context.getString(R.string.queue_added))
    }
    fun addItemNext(item: Music) = withTracks(item) { tracks ->
        model.player.playNext(tracks)
        sendToast(context.getString(R.string.queue_next))
    }

    /** Generating a radio takes a moment: say so, then open it once it starts playing. */
    fun startRadio(item: Music, open: (Music) -> Unit) {
        sendToast(context.getString(R.string.radio_preparing))
        model.loadRadio(item, open)
    }

    fun togglePin(item: Music) {
        val pinned = model.pins.value.any { it.id == item.id }
        model.togglePin(item)
        sendToast(if (pinned) context.getString(R.string.pin_removed) else context.getString(R.string.pin_added))
    }

    // Search history ----------------------------------------------------------------------------
    // What was opened or played from search, most recent first, as the official app keeps it.
    private val mutableHistory = MutableStateFlow(readHistory())
    val searchHistory: StateFlow<List<Music>> = mutableHistory.asStateFlow()
    private fun readHistory(): List<Music> {
        // Typed queries were kept before; they are not results and are dropped.
        if (uiPrefs.contains(KEY_HISTORY)) uiPrefs.edit { remove(KEY_HISTORY) }
        return fr.spotifeur.data.decodePins(uiPrefs.getString(KEY_HISTORY_ITEMS, null))
    }
    private fun writeHistory(items: List<Music>) {
        mutableHistory.value = items
        uiPrefs.edit { putString(KEY_HISTORY_ITEMS, fr.spotifeur.data.encodePins(items)) }
    }
    fun rememberSearchResult(item: Music) =
        writeHistory((listOf(item) + mutableHistory.value.filterNot { it.id == item.id }).take(MAX_HISTORY))
    fun forgetSearch(item: Music) = writeHistory(mutableHistory.value.filterNot { it.id == item.id })
    fun clearSearchHistory() = writeHistory(emptyList())

    // Queue -------------------------------------------------------------------------------------
    fun addSongToQueue(song: Song) {
        model.player.addToQueue(listOf(song.toMusic()))
        sendToast(context.getString(R.string.queue_added))
    }
    fun addSongNextToQueue(song: Song) {
        model.player.playNext(listOf(song.toMusic()))
        sendToast(context.getString(R.string.queue_next))
    }

    fun removeSongFromQueue(songId: String) {
        val state = model.player.state.value
        val index = state.queue.indices.firstOrNull { state.queue[it].id == songId && it != state.index } ?: return
        val removed = state.queue[index]
        model.player.removeAt(index)
        undoJob?.cancel()
        mutableUi.update { it.copy(showQueueItemUndoBar = true, lastRemovedQueueSong = removed.toSong(), lastRemovedQueueIndex = index) }
        undoJob = scope.launch { delay(QUEUE_UNDO_MS); hideQueueItemUndoBar() }
    }
    fun undoRemoveSongFromQueue() {
        val ui = mutableUi.value
        ui.lastRemovedQueueSong?.let { model.player.insert(ui.lastRemovedQueueIndex, listOf(it.toMusic())) }
        hideQueueItemUndoBar()
    }
    fun hideQueueItemUndoBar() {
        mutableUi.update { it.copy(showQueueItemUndoBar = false, lastRemovedQueueSong = null, lastRemovedQueueIndex = -1) }
    }
    fun reorderQueueItem(from: Int, to: Int) = model.player.move(from, to)
    fun clearQueueExceptCurrent() = model.player.clearExceptCurrent()

    // Mini player dismissal ----------------------------------------------------------------------
    private var undoJob: Job? = null
    private var dismissJob: Job? = null
    private var dismissed: Triple<List<Music>, Int, Int>? = null

    fun dismissPlaylistAndShowUndo() {
        val state = model.player.state.value
        val (context, index) = model.player.context()
        dismissed = Triple(context, index, state.positionMs)
        model.player.stop()
        mutableMiniDismissing.value = false
        mutableUi.update { it.copy(showDismissUndoBar = true) }
        dismissJob?.cancel()
        dismissJob = scope.launch { delay(mutableUi.value.undoBarVisibleDuration); hideDismissUndoBar() }
    }
    fun undoDismissPlaylist() {
        dismissed?.let { (queue, index, position) ->
            model.play(queue, index)
            scope.launch { delay(UNDO_SEEK_DELAY_MS); model.player.seek(position) }
        }
        hideDismissUndoBar()
    }
    fun hideDismissUndoBar() {
        dismissJob?.cancel()
        dismissed = null
        mutableUi.update { it.copy(showDismissUndoBar = false) }
    }

    // Sleep timer -------------------------------------------------------------------------------
    private var sleepJob: Job? = null
    private val mutableTimerDisplay = MutableStateFlow<String?>(null)
    val activeTimerValueDisplay: StateFlow<String?> = mutableTimerDisplay.asStateFlow()
    private val mutableTimerMinutes = MutableStateFlow<Int?>(null)
    val activeTimerDurationMinutes: StateFlow<Int?> = mutableTimerMinutes.asStateFlow()
    private val mutableEndOfTrack = MutableStateFlow(false)
    val isEndOfTrackTimerActive: StateFlow<Boolean> = mutableEndOfTrack.asStateFlow()

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        mutableTimerMinutes.value = minutes
        sleepJob = scope.launch {
            var remaining = minutes * 60
            while (remaining > 0) {
                mutableTimerDisplay.value = "%d:%02d".format(remaining / 60, remaining % 60)
                delay(1000)
                remaining--
            }
            model.player.pause()
            cancelSleepTimer()
        }
    }
    fun setEndOfTrackTimer(enable: Boolean) {
        cancelSleepTimer()
        if (!enable) return
        mutableEndOfTrack.value = true
        mutableTimerDisplay.value = context.getString(R.string.timer_end_track)
        val songId = stablePlayerState.value.currentSong?.id
        sleepJob = scope.launch {
            stablePlayerState.first { it.currentSong?.id != songId }
            model.player.pause()
            cancelSleepTimer()
        }
    }
    private val mutablePlayCount = MutableStateFlow(0f)
    val playCount: StateFlow<Float> = mutablePlayCount.asStateFlow()
    private var countedJob: Job? = null

    /** Pauses once [count] more tracks have started. */
    fun playCounted(count: Int) {
        cancelCountedPlay()
        if (count <= 0) return
        mutablePlayCount.value = count.toFloat()
        countedJob = scope.launch {
            var remaining = count
            stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged().drop(1).collect {
                remaining--
                mutablePlayCount.value = remaining.toFloat()
                if (remaining <= 0) { model.player.pause(); cancelCountedPlay() }
            }
        }
    }
    fun cancelCountedPlay() {
        countedJob?.cancel()
        countedJob = null
        mutablePlayCount.value = 0f
    }
    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        mutableTimerDisplay.value = null
        mutableTimerMinutes.value = null
        mutableEndOfTrack.value = false
    }

    // Downloads ---------------------------------------------------------------------------------
    val downloads: StateFlow<Map<String, DownloadedTrack>> = DownloadCenter.downloads
    val downloadCollections = DownloadCenter.collections
    val requestedLibraryTab = MutableStateFlow<String?>(null)
    private val downloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val mutableDownloadPreparations = MutableStateFlow<Set<String>>(emptySet())
    val downloadPreparations = mutableDownloadPreparations.asStateFlow()

    /** Keeps the Spotify tracks of [item] on this device, stored as delivered: encrypted. */
    fun downloadItem(item: Music) {
        if (item.id in downloadJobs) return
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val all = model.tracksOf(item).filterNot(Music::isLocalTrack)
                if (all.isEmpty()) { sendToast(context.getString(R.string.no_tracks_for, item.title)); return@launch }
                // Spotify keeps its own files only for accounts allowed to; YouTube Music files are always kept.
                val throughSpotify = fr.spotifeur.data.SpotifyAccount.signedIn && all.any { it.id.startsWith("spotify:track:") }
                val allowed = !throughSpotify || model.offlineAllowed()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val tracks = if (allowed) all else all.filter { it.source == fr.spotifeur.data.MusicSource.YouTubeMusic }
                if (tracks.isEmpty()) { sendToast(context.getString(R.string.download_not_allowed)); return@launch }
                DownloadCenter.enqueue(context, item, tracks) {
                    sendToast(context.getString(R.string.download_failed, item.title))
                }
                sendToast(context.getString(R.string.download_started))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { sendToast(context.getString(R.string.download_failed, item.title)) }
            finally {
                if (downloadJobs[item.id] === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                    downloadJobs.remove(item.id)
                    mutableDownloadPreparations.value = downloadJobs.keys.toSet()
                }
            }
        }
        downloadJobs[item.id] = job
        mutableDownloadPreparations.value = downloadJobs.keys.toSet()
        job.start()
    }
    fun toggleDownloadItem(item: Music) {
        if (item.id in downloadJobs || item.id in downloadCollections.value) removeDownloads(item) else downloadItem(item)
    }
    fun removeDownloads(item: Music) {
        downloadJobs.remove(item.id)?.cancel()
        mutableDownloadPreparations.value = downloadJobs.keys.toSet()
        if (downloadCollections.value[item.id]?.isComplete == true) DownloadCenter.remove(context, item.id)
        else DownloadCenter.cancel(context, item.id)
    }
    fun removeAllDownloads() {
        val jobs = downloadJobs.values.toList()
        downloadJobs.clear(); mutableDownloadPreparations.value = emptySet()
        jobs.forEach { it.cancel() }
        DownloadCenter.removeAll(context)
    }
    fun downloadsSizeBytes(): Long = DownloadCenter.sizeBytes(context)
    val downloadsStoredBytes = DownloadCenter.storedBytes

    // Recent audio and catalogue kept on the device ----------------------------------------------
    fun audioCacheEntries(): List<CachedAudio> = AudioCache.entries(context)
    fun audioCacheBytes(): Long = AudioCache.usedBytes(context)
    fun removeFromAudioCache(ids: Collection<String>) = AudioCache.remove(context, ids)
    fun clearAudioCache() = AudioCache.clear(context)
    fun setAudioCacheLimit(megabytes: Int) {
        updateSettings { it.copy(audioCacheLimitMb = megabytes) }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { AudioCache.setLimit(context, megabytes) }
    }
    fun catalogBytes(): Long = model.catalogStore.sizeBytes() + fr.spotifeur.data.AudioIndex.get(context).sizeBytes()
    fun clearCatalog() { model.catalogStore.clear() }
    fun freeStorageBytes(): Long = context.noBackupFilesDir.usableSpace

    // Backup ------------------------------------------------------------------------------------
    private val backups by lazy { BackupManager(context, model.pinnedStoreForBackup, model.localPlaylistStore) }
    suspend fun exportBackup(): String = backups.export(searchHistory.value)
    suspend fun restoreBackup(content: String) {
        writeHistory(backups.restore(content))
        AppLocales.apply(context, settings.value.language)
    }

    // Lyrics ------------------------------------------------------------------------------------
    private val lyricsRepository by lazy { LyricsRepository(context) }
    private val mutableLyricsSearch = MutableStateFlow<LyricsSearchUiState>(LyricsSearchUiState.Idle)
    val lyricsSearchUiState: StateFlow<LyricsSearchUiState> = mutableLyricsSearch.asStateFlow()
    val lyricsSyncOffset: StateFlow<Int> = mutableLyrics.map { it.offset }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 0)
    private val mutableImmersiveDisabled = MutableStateFlow(false)
    val isImmersiveTemporarilyDisabled: StateFlow<Boolean> = mutableImmersiveDisabled.asStateFlow()
    private var lyricsJob: Job? = null

    /** Loads the current track's lyrics: saved ones, then Spotify, then LRCLIB if the setting allows. */
    fun loadLyricsForCurrentSong() {
        val song = stablePlayerState.value.currentSong ?: return
        val known = mutableLyrics.value
        if (known.songId == song.id && (known.loading || known.lyrics != null)) return
        val offset = uiPrefs.getInt(KEY_LYRICS_OFFSET + song.id, 0)
        lyricsJob?.cancel()
        mutableLyrics.value = LyricsState(song.id, loading = true, offset = offset)
        lyricsJob = scope.launch {
            val lyrics = lyricsRepository.cached(song.id)
                ?: lyricsRepository.fromSpotify(song, model.lyricsSource())
                ?: if (settings.value.lyricsFallback) lyricsRepository.fromLrcLib(song) else null
            mutableLyrics.value = LyricsState(song.id, lyrics = lyrics, offset = offset)
        }
    }
    /** Called by the "fetch" dialog: the source chain, then its picker when asked for. */
    fun fetchLyricsForCurrentSong(forcePick: Boolean) {
        val song = stablePlayerState.value.currentSong ?: return
        lyricsJob?.cancel()
        mutableLyricsSearch.value = LyricsSearchUiState.Loading
        lyricsJob = scope.launch {
            val direct = if (forcePick) null else lyricsRepository.fromSpotify(song, model.lyricsSource()) ?: lyricsRepository.fromLrcLib(song)
            if (direct != null) {
                mutableLyrics.update { it.copy(songId = song.id, lyrics = direct, loading = false) }
                mutableLyricsSearch.value = LyricsSearchUiState.Success(direct)
            } else showLyricsResults(song.title, song.artist)
        }
    }
    fun searchLyricsManually(title: String, artist: String?) {
        lyricsJob?.cancel()
        mutableLyricsSearch.value = LyricsSearchUiState.Loading
        lyricsJob = scope.launch { showLyricsResults(title, artist) }
    }
    private suspend fun showLyricsResults(title: String, artist: String?) {
        val results = lyricsRepository.search(title, artist)
        mutableLyricsSearch.value = if (results.isEmpty()) LyricsSearchUiState.NotFound(context.getString(R.string.lyrics_not_found))
        else LyricsSearchUiState.PickResult(title, results)
    }
    fun acceptLyricsSearchResultForCurrentSong(result: LyricsSearchResult) {
        val song = stablePlayerState.value.currentSong ?: return
        scope.launch {
            lyricsRepository.store(song.id, result.rawLyrics)
            mutableLyrics.update { it.copy(songId = song.id, lyrics = result.lyrics, loading = false) }
            mutableLyricsSearch.value = LyricsSearchUiState.Success(result.lyrics)
        }
    }
    fun importLyricsFromFile(import: ValidatedLyricsImport) {
        val song = stablePlayerState.value.currentSong ?: return
        scope.launch {
            lyricsRepository.store(song.id, import.sanitizedContent)
            mutableLyrics.update { it.copy(songId = song.id, lyrics = import.parsedLyrics, loading = false) }
        }
    }
    fun resetLyricsForCurrentSong() {
        val song = stablePlayerState.value.currentSong ?: return
        scope.launch { lyricsRepository.forget(song.id) }
        mutableLyrics.update { it.copy(lyrics = null, loading = false) }
    }
    fun resetLyricsSearchState() { mutableLyricsSearch.value = LyricsSearchUiState.Idle }
    fun setLyricsSyncOffset(songId: String, offset: Int) {
        uiPrefs.edit { putInt(KEY_LYRICS_OFFSET + songId, offset) }
        mutableLyrics.update { if (it.songId == songId) it.copy(offset = offset) else it }
    }
    fun setImmersiveTemporarilyDisabled(value: Boolean) { mutableImmersiveDisabled.value = value }
    fun clearSavedLyrics() { scope.launch { lyricsRepository.clear() } }

    // Messages ----------------------------------------------------------------------------------
    private val mutableToasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastEvents: SharedFlow<String> = mutableToasts.asSharedFlow()
    fun sendToast(message: String) { mutableToasts.tryEmit(message) }

    companion object {
        private const val KEY_HISTORY = "search_history"
        private const val KEY_HISTORY_ITEMS = "search_history_items"
        private const val KEY_LYRICS_OFFSET = "lyrics_offset_"
        const val KEY_PLAYLIST_SORT = "playlist_sort_"
        private const val KEY_QUALITY = "measured_quality"
        private const val MAX_HISTORY = 30
        private const val QUEUE_UNDO_MS = 4000L
        private const val UNDO_SEEK_DELAY_MS = 600L
    }
}

enum class PlaylistSort { DEFAULT, REVERSED, TITLE }

/** Title order follows the reader's language: accents, case and leading punctuation do not split the alphabet. */
fun <T> List<T>.sortedForPlaylist(sort: PlaylistSort, title: (T) -> String): List<T> = when (sort) {
    PlaylistSort.DEFAULT -> this
    PlaylistSort.REVERSED -> asReversed().toList()
    PlaylistSort.TITLE -> {
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        sortedWith { first, second -> collator.compare(sortableTitle(title(first)), sortableTitle(title(second))) }
    }
}

/** Leading quotes, brackets and punctuation do not decide where a title files. */
fun sortableTitle(title: String): String = title.dropWhile { !it.isLetterOrDigit() }.ifEmpty { title }
