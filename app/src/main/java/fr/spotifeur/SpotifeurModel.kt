package fr.spotifeur

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.InvalidProtocolBufferException
import fr.spotifeur.data.CatalogDetail
import fr.spotifeur.data.CatalogStore
import fr.spotifeur.data.FavoriteChange
import fr.spotifeur.data.NetworkMonitor
import fr.spotifeur.data.SpotifyAuthRequired
import fr.spotifeur.data.applyFavoriteChanges
import fr.spotifeur.data.playlistMosaic
import fr.spotifeur.data.decodePins
import fr.spotifeur.data.encodePins
import fr.spotifeur.data.HomeLibrary
import fr.spotifeur.data.LOCAL_PLAYLIST_PREFIX
import fr.spotifeur.data.LocalLibrary
import fr.spotifeur.data.LocalPlaylistStore
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.isLocalPlaylist
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.data.source
import fr.spotifeur.data.PinnedStore
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.spotifyAttribute
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import fr.spotifeur.data.SpotifyCatalog
import fr.spotifeur.data.SpotifyPlaylists
import fr.spotifeur.data.SpotifyLibrary
import fr.spotifeur.data.SpotifyLyricsSource
import fr.spotifeur.data.SpotifyRadio
import fr.spotifeur.playback.SpotifyPlayer
import fr.spotifeur.data.SessionVault
import fr.spotifeur.data.Music
import fr.spotifeur.data.SpotifyFailure
import fr.spotifeur.data.BackgroundRequests
import fr.spotifeur.data.SpotifySearch
import fr.spotifeur.data.SpotifySession
import fr.spotifeur.data.SpotifyTransport
import fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX
import fr.spotifeur.data.youtube.YouTubeAccount
import fr.spotifeur.data.youtube.YouTubeAccountInfo
import fr.spotifeur.data.youtube.YouTubeAccountPlaylist
import fr.spotifeur.data.youtube.YouTubeAuthRequired
import fr.spotifeur.data.youtube.YouTubeFailure
import fr.spotifeur.data.youtube.YouTubeLibrary
import fr.spotifeur.data.youtube.youTubeVideoId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** What a search reads. [All], the default, mixes Spotify (with an account), YouTube Music and device files. */
enum class SearchSource { All, Spotify, YouTubeMusic, Local }

data class Discovery(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Music> = emptyList(),
    /** Spotify's next page token. */
    val nextPage: String? = null,
    /** YouTube Music's continuation token. */
    val youTubeNext: String? = null,
    val error: String? = null,
    val spotifyEnabled: Boolean = false,
    val source: SearchSource = SearchSource.All,
) {
    /** The source actually searched: Spotify needs an account. */
    val activeSource: SearchSource get() = if (!spotifyEnabled && source == SearchSource.Spotify) SearchSource.All else source
    val hasMore: Boolean get() = nextPage != null || youTubeNext != null
}

data class AccountUi(
    val working: Boolean = false,
    val codeLength: Int? = null,
    val codeEmail: String? = null,
    val error: String? = null,
    val restoring: Boolean = false,
)

data class LibraryUi(val content: HomeLibrary? = null, val loading: Boolean = false, val error: String? = null) {
    val items: List<Music> get() = content?.let { it.playlists + it.albums + it.artists }.orEmpty()
    val tracks: List<Music> get() = content?.savedTracks.orEmpty()
}
data class DetailUi(val content: CatalogDetail? = null, val loading: Boolean = false, val error: String? = null)

class SpotifeurModel(private val app: Application) : AndroidViewModel(app) {
    val player = SpotifyPlayer(app, viewModelScope)
    private val pinnedStore = PinnedStore(app)
    val pins = pinnedStore.pins
    fun togglePin(item: Music) = pinnedStore.toggle(item)
    fun movePin(from: Int, to: Int) = pinnedStore.move(from, to)
    val ui by lazy { PlayerViewModel(this, viewModelScope) }
    val localLibrary = LocalLibrary(app)
    private val mutableLocalTracks = MutableStateFlow<List<Music>>(emptyList())
    val localTracks = mutableLocalTracks.asStateFlow()
    val localPlaylistStore = LocalPlaylistStore(app)
    val listeningHistory = fr.spotifeur.data.ListeningHistoryStore(app)
    val localPlaylists = localPlaylistStore.playlists
    fun refreshLocalTracks() { viewModelScope.launch { mutableLocalTracks.value = localLibrary.load() } }
    val pinnedStoreForBackup get() = pinnedStore
    private val preferences = app.getSharedPreferences("music_library", 0)
    private val savedIds = MutableStateFlow<Set<String>>(emptySet())
    val favorites = savedIds.asStateFlow()

    private val mutableDiscovery = MutableStateFlow(Discovery())
    val discovery = mutableDiscovery.asStateFlow()
    private var searchJob: Job? = null
    private var accountJob: Job? = null
    private val mutableAccount = MutableStateFlow(AccountUi())
    val account = mutableAccount.asStateFlow()
    /** The app is used without a Spotify account: local files, YouTube Music and multi-service playlists. */
    private val mutableGuest = MutableStateFlow(app.getSharedPreferences("music_library", 0).getBoolean(KEY_GUEST, false))
    val guest = mutableGuest.asStateFlow()
    fun continueWithoutAccount() {
        preferences.edit { putBoolean(KEY_GUEST, true) }
        mutableGuest.value = true
        if (remote == null) mutableLibrary.value = LibraryUi(emptyLibrary())
        search(discovery.value.query)
    }
    /** Back to the sign-in screen, from the settings of a guest. */
    fun showSignIn() {
        preferences.edit { putBoolean(KEY_GUEST, false) }
        mutableGuest.value = false
    }
    private val transport = SpotifyTransport()
    private val vault = SessionVault(app)
    private val devicePreferences = app.getSharedPreferences("spotify_device", 0)
    private val deviceId = devicePreferences.getString("id", null) ?: UUID.randomUUID().toString().also { id ->
        devicePreferences.edit { putString("id", id) }
    }
    private var pendingCode: CompletableDeferred<String>? = null
    private val loginFlowId by lazy { UUID.randomUUID().toString() }
    private var remote: SpotifySearch? = null
    private var catalog: SpotifyCatalog? = null
    private var collections: SpotifyLibrary? = null
    private var radio: SpotifyRadio? = null
    private var libraryJob: Job? = null
    private val detailJobs = mutableMapOf<String, Job>()
    private val pendingMutations = mutableSetOf<String>()
    private val store = CatalogStore(app)
    internal val catalogStore get() = store
    /** Likes the library read does not show yet. Main thread only. */
    private val favoriteChanges = LinkedHashMap<String, FavoriteChange>()
    /** Mosaic covers of playlists without their own picture, from their stored pages. Main thread only. */
    private val playlistCovers = HashMap<String, String>()
    private fun rememberCover(detail: CatalogDetail) {
        val item = detail.item
        if (item.kind != MusicKind.Playlist || item.imageUrl == null || playlistCovers[item.id] == item.imageUrl) return
        val shown = mutableLibrary.value.content?.playlists?.firstOrNull { it.id == item.id } ?: return
        if (shown.imageUrl != null && shown.id !in playlistCovers) return
        playlistCovers[item.id] = item.imageUrl
        saveCovers()
        mutableLibrary.value.content?.let { installLibrary(it, mutableLibrary.value.loading) }
    }
    private val coverLookups = HashSet<String>()
    /** A playlist without picture whose page is on the device gets its mosaic without a request. */
    private fun coverFromStoredPage(id: String) {
        if (!coverLookups.add(id)) return
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { store.loadDetail(id) } ?: return@launch
            val cover = playlistMosaic(detail.tracks) ?: return@launch
            playlistCovers[id] = cover
            saveCovers()
            mutableLibrary.value.content?.let { installLibrary(it, mutableLibrary.value.loading) }
        }
    }
    private fun saveCovers() = preferences.edit { putString(KEY_PLAYLIST_COVERS, org.json.JSONObject(playlistCovers as Map<*, *>).toString()) }
    private fun loadCovers() {
        val json = runCatching { org.json.JSONObject(preferences.getString(KEY_PLAYLIST_COVERS, null) ?: return) }.getOrNull() ?: return
        json.keys().forEach { playlistCovers[it] = json.getString(it) }
    }
    private var offlineSyncJob: Job? = null
    val online = NetworkMonitor.online
    private val mutableLibrary = MutableStateFlow(LibraryUi())
    val library = mutableLibrary.asStateFlow()
    private val mutableDetails = MutableStateFlow<Map<String, DetailUi>>(emptyMap())
    val details = mutableDetails.asStateFlow()
    private val mutableRecent = MutableStateFlow<List<Music>>(emptyList())
    val recent = mutableRecent.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    // YouTube Music account ----------------------------------------------------------------------

    val youTubeAccount = YouTubeAccount.info
    private val mutableYouTubePlaylists = MutableStateFlow(decodePins(preferences.getString(KEY_YOUTUBE_PLAYLISTS, null)))
    /** The Google account's YouTube Music playlists, as last read; kept on the device between starts. */
    val youTubePlaylists = mutableYouTubePlaylists.asStateFlow()
    private val mutableYouTubeLoading = MutableStateFlow(false)
    val youTubeLoading = mutableYouTubeLoading.asStateFlow()
    private val mutableYouTubeEditable = MutableStateFlow(preferences.getStringSet(KEY_YOUTUBE_EDITABLE, null).orEmpty().toSet())
    /** Playlists whose page showed the owner's editing header. */
    val youTubeEditable = mutableYouTubeEditable.asStateFlow()
    /** Row identifiers of the account's playlists as last read, to remove one row. Main thread only. */
    private val youTubeRows = HashMap<String, YouTubeAccountPlaylist>()
    private var youTubeJob: Job? = null

    /** Checks the cookies of Google's sign-in page, keeps them, then reads the library. */
    suspend fun connectGoogle(cookie: String, visitorData: String?, dataSyncId: String?): YouTubeAccountInfo {
        val info = withContext(Dispatchers.IO) { YouTubeAccount.connect(cookie, visitorData, dataSyncId) }
        // The cookies are now kept encrypted: the WebView's own copy is removed.
        runCatching { android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() } }
        refreshYouTubeLibrary()
        return info
    }

    /** Forgets the Google account on this device. Its playlists stay on YouTube Music. */
    fun disconnectGoogle() {
        youTubeJob?.cancel()
        YouTubeAccount.clear()
        runCatching { android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() } }
        val ids = mutableYouTubePlaylists.value.map(Music::id).toSet()
        ids.forEach { detailJobs.remove(it)?.cancel() }
        mutableYouTubePlaylists.value = emptyList()
        mutableYouTubeEditable.value = emptySet()
        youTubeRows.clear()
        preferences.edit { remove(KEY_YOUTUBE_PLAYLISTS); remove(KEY_YOUTUBE_EDITABLE) }
        mutableDetails.update { it - ids }
        // Pages read with the account (private playlists included) do not stay on the device.
        viewModelScope.launch(Dispatchers.IO) { ids.forEach(store::removeDetail) }
    }

    private fun youTubeAccountLost(failure: YouTubeAuthRequired) {
        disconnectGoogle()
        mutableMessage.value = app.getString(fr.spotifeur.R.string.google_session_closed)
        fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-account", "signed out: ${failure.message}")
    }

    fun refreshYouTubeLibrary() {
        if (!YouTubeAccount.signedIn || !online.value || youTubeJob?.isActive == true) return
        youTubeJob = viewModelScope.launch {
            mutableYouTubeLoading.value = true
            try {
                val playlists = withContext(Dispatchers.IO) { YouTubeLibrary.libraryPlaylists() }
                installYouTubePlaylists(playlists)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth) }
            catch (failure: Exception) {
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-library", "${failure.javaClass.simpleName} \"${failure.message}\"")
            } finally { mutableYouTubeLoading.value = false }
        }
    }

    private fun installYouTubePlaylists(playlists: List<Music>) {
        // A playlist without a cover of its own shows the covers of its tracks, once its page is known.
        val shown = playlists.map { playlist ->
            if (playlist.imageUrl != null) playlist
            else mutableDetails.value[playlist.id]?.content?.item?.imageUrl?.let { playlist.copy(imageUrl = it) } ?: playlist
        }
        shown.forEach { ui.register(it) }
        mutableYouTubePlaylists.value = shown
        preferences.edit { putString(KEY_YOUTUBE_PLAYLISTS, encodePins(shown)) }
    }

    private fun markYouTubeEditable(id: String, editable: Boolean) {
        val next = if (editable) mutableYouTubeEditable.value + id else mutableYouTubeEditable.value - id
        if (next == mutableYouTubeEditable.value) return
        mutableYouTubeEditable.value = next
        preferences.edit { putStringSet(KEY_YOUTUBE_EDITABLE, next) }
    }

    /** Whether the signed-in account can change this YouTube Music playlist. */
    fun youTubePlaylistOwned(item: Music): Boolean {
        if (!item.id.startsWith(YTM_PLAYLIST_PREFIX) || youTubeAccount.value == null) return false
        if (item.id in youTubeEditable.value) return true
        val listed = mutableYouTubePlaylists.value.firstOrNull { it.id == item.id } ?: item
        return item.id != YTM_PLAYLIST_PREFIX + "LM" && listed.ownerUsername != null && listed.ownerUsername == youTubeAccount.value?.name
    }

    /** Reads a YouTube Music playlist: with the account when there is one (private playlists, row ids), anonymously otherwise. */
    private suspend fun fetchYouTubePlaylist(item: Music): CatalogDetail {
        val id = item.id.removePrefix(YTM_PLAYLIST_PREFIX)
        val (title, image, tracks) = if (YouTubeAccount.signedIn) {
            val playlist = withContext(Dispatchers.IO) { YouTubeLibrary.playlist(id) }
            youTubeRows[item.id] = playlist
            markYouTubeEditable(item.id, playlist.editable)
            Triple(playlist.title, playlist.imageUrl, playlist.tracks)
        } else {
            val playlist = withContext(Dispatchers.IO) { fr.spotifeur.data.youtube.YouTubeMusic.playlist(id) }
            Triple(playlist.title, playlist.imageUrl, playlist.tracks)
        }
        val music = item.copy(title = title.ifBlank { item.title }, subtitle = fr.spotifeur.utils.formatSongCount(tracks.size),
            imageUrl = image ?: playlistMosaic(tracks) ?: item.imageUrl, tracks = tracks.map(Music::id))
        val detail = CatalogDetail(music, tracks, total = tracks.size)
        installYouTubeDetail(detail)
        return detail
    }

    private suspend fun installYouTubeDetail(detail: CatalogDetail) {
        val item = detail.item
        ui.register(item)
        mutableDetails.update { it + (item.id to DetailUi(detail)) }
        withContext(Dispatchers.IO) { store.saveDetail(item.id, detail) }
        val listed = mutableYouTubePlaylists.value
        if (listed.any { it.id == item.id }) installYouTubePlaylists(listed.map {
            if (it.id == item.id) it.copy(title = item.title, imageUrl = it.imageUrl ?: item.imageUrl) else it
        })
    }

    private fun loadYouTubePlaylist(item: Music, refresh: Boolean) {
        val shown = mutableDetails.value[item.id]
        if (!refresh && shown?.content != null && shown.error == null) return
        if (shown?.loading == true && !refresh) return
        detailJobs[item.id]?.cancel()
        detailJobs[item.id] = viewModelScope.launch {
            var known = shown?.content
            if (known == null) known = withContext(Dispatchers.IO) { store.loadDetail(item.id) }
            mutableDetails.update { it + (item.id to DetailUi(known, loading = online.value)) }
            if (!online.value) {
                if (known == null) mutableDetails.update { it + (item.id to DetailUi(error = app.getString(fr.spotifeur.R.string.youtube_playlist_failed))) }
                return@launch
            }
            try { fetchYouTubePlaylist(item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); mutableDetails.update { it + (item.id to DetailUi(known)) } }
            catch (failure: Exception) {
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-playlist", "${failure.javaClass.simpleName} \"${failure.message}\"")
                mutableDetails.update { it + (item.id to if (known != null) DetailUi(known)
                    else DetailUi(error = app.getString(fr.spotifeur.R.string.youtube_playlist_failed))) }
            }
        }
    }

    /** YouTube Music album and artist pages: kept on the device like Spotify's, read again when older than a day. */
    private fun loadYouTubePage(item: Music, refresh: Boolean) {
        val shown = mutableDetails.value[item.id]
        if (!refresh && shown?.content != null && shown.error == null) return
        if (shown?.loading == true && !refresh) return
        detailJobs[item.id]?.cancel()
        detailJobs[item.id] = viewModelScope.launch {
            val known = shown?.content ?: withContext(Dispatchers.IO) { store.loadDetail(item.id) }
            val age = withContext(Dispatchers.IO) { store.detailAgeMs(item.id) }
            if (known != null && (!online.value || (!refresh && age < YOUTUBE_PAGE_TTL_MS))) {
                mutableDetails.update { it + (item.id to DetailUi(known)) }
                return@launch
            }
            mutableDetails.update { it + (item.id to DetailUi(known, loading = true)) }
            try {
                val page = withContext(Dispatchers.IO) {
                    if (item.id.startsWith(fr.spotifeur.data.youtube.YTM_ALBUM_PREFIX))
                        fr.spotifeur.data.youtube.YouTubePages.album(item.id.removePrefix(fr.spotifeur.data.youtube.YTM_ALBUM_PREFIX))
                    else fr.spotifeur.data.youtube.YouTubePages.artist(item.id.removePrefix(fr.spotifeur.data.youtube.YTM_ARTIST_PREFIX))
                }
                (page.related + page.item).forEach(ui::register)
                mutableDetails.update { it + (item.id to DetailUi(page)) }
                withContext(Dispatchers.IO) { store.saveDetail(item.id, page) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-page", "${item.kind.name.lowercase()} ${failure.javaClass.simpleName} \"${failure.message}\"")
                mutableDetails.update { it + (item.id to if (known != null) DetailUi(known) else DetailUi(error = readable(failure))) }
            }
        }
    }

    private suspend fun youTubeTracks(item: Music): List<Music> {
        mutableDetails.value[item.id]?.content?.takeIf { it.nextPage == null }?.let { return it.tracks }
        if (online.value) try { return fetchYouTubePlaylist(item).tracks }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth) }
        catch (_: IOException) { /* What the device has is used. */ }
        return withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.tracks.orEmpty()
    }

    /** Creates a playlist on the Google account. Only YouTube Music tracks go in. */
    internal suspend fun createYouTubePlaylist(name: String, tracks: List<Music>, public: Boolean): Music {
        val wanted = tracks.filter { it.youTubeVideoId != null }
        val result = try {
            withContext(Dispatchers.IO) {
                YouTubeLibrary.create(name, wanted.mapNotNull { it.youTubeVideoId }, if (public) YouTubeLibrary.PUBLIC else YouTubeLibrary.PRIVATE)
            }
        } catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); throw auth }
        val id = result.id
        val kept = wanted.filterNot { it.youTubeVideoId in result.refused }
        reportRefused(result.refused.size)
        val created = Music(YTM_PLAYLIST_PREFIX + id, name.trim(), fr.spotifeur.utils.formatSongCount(kept.size), MusicKind.Playlist,
            imageUrl = playlistMosaic(kept) ?: kept.firstNotNullOfOrNull(Music::imageUrl), tracks = kept.map(Music::id),
            ownerUsername = youTubeAccount.value?.name)
        markYouTubeEditable(created.id, true)
        installYouTubePlaylists(listOf(created) + mutableYouTubePlaylists.value.filterNot { it.id == created.id })
        installYouTubeDetail(CatalogDetail(created, kept))
        return created
    }

    internal suspend fun addYouTubePlaylistTracks(playlist: Music, tracks: List<Music>) {
        val kept = tracks.filter { it.youTubeVideoId != null }
        if (kept.isEmpty()) throw IllegalArgumentException(app.getString(fr.spotifeur.R.string.youtube_playlist_only_youtube))
        val refused = try { withContext(Dispatchers.IO) { YouTubeLibrary.add(playlist.id, kept.mapNotNull { it.youTubeVideoId }) } }
        catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); throw auth }
        reportRefused(refused.size)
        detailJobs.remove(playlist.id)?.cancel()
        // New rows have ids only the page knows: the next removal reads it again.
        youTubeRows.remove(playlist.id)
        val content = mutableDetails.value[playlist.id]?.content ?: CatalogDetail(playlist, emptyList())
        val tracksNow = content.tracks + kept.filterNot { it.youTubeVideoId in refused }
        installYouTubeDetail(content.copy(item = content.item.copy(subtitle = fr.spotifeur.utils.formatSongCount(tracksNow.size),
            imageUrl = content.item.imageUrl ?: playlistMosaic(tracksNow)), tracks = tracksNow, total = tracksNow.size))
    }

    private fun reportRefused(count: Int) {
        if (count > 0) mutableMessage.value = app.resources.getQuantityString(fr.spotifeur.R.plurals.youtube_tracks_refused, count, count)
    }

    /** Removes the row at [index] of the playlist as shown; other copies of the same track stay. */
    internal suspend fun removeYouTubePlaylistTrack(playlist: Music, index: Int) {
        val content = mutableDetails.value[playlist.id]?.content ?: return
        val track = content.tracks.getOrNull(index) ?: return
        val videoId = track.youTubeVideoId ?: return
        val occurrence = content.tracks.take(index).count { it.id == track.id }
        fun rowOf(rows: YouTubeAccountPlaylist) = rows.tracks.withIndex().filter { it.value.id == track.id }.getOrNull(occurrence)?.index
        try {
            var rows = youTubeRows[playlist.id]?.takeIf { it.tracks.map(Music::id) == content.tracks.map(Music::id) }
            if (rows == null) rows = withContext(Dispatchers.IO) { YouTubeLibrary.playlist(playlist.id) }
            val row = rowOf(rows) ?: throw YouTubeFailure(app.getString(fr.spotifeur.R.string.youtube_playlist_row_gone))
            val setVideoId = rows.setVideoIds[row] ?: throw YouTubeFailure(app.getString(fr.spotifeur.R.string.youtube_playlist_row_gone))
            withContext(Dispatchers.IO) { YouTubeLibrary.remove(playlist.id, videoId, setVideoId) }
            detailJobs.remove(playlist.id)?.cancel()
            val remaining = rows.copy(tracks = rows.tracks.filterIndexed { i, _ -> i != row },
                setVideoIds = rows.setVideoIds.filterIndexed { i, _ -> i != row })
            youTubeRows[playlist.id] = remaining
            val item = content.item.copy(subtitle = fr.spotifeur.utils.formatSongCount(remaining.tracks.size), tracks = remaining.tracks.map(Music::id))
            installYouTubeDetail(content.copy(item = item, tracks = remaining.tracks, total = remaining.tracks.size))
        } catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); throw auth }
    }

    /** Whether the playlist is public, read from its page. */
    internal suspend fun youTubePlaylistPublic(playlist: Music): Boolean {
        val rows = youTubeRows[playlist.id] ?: withContext(Dispatchers.IO) { YouTubeLibrary.playlist(playlist.id) }.also { youTubeRows[playlist.id] = it }
        return rows.privacy == YouTubeLibrary.PUBLIC
    }

    internal suspend fun editYouTubePlaylist(playlist: Music, name: String, public: Boolean) {
        try {
            withContext(Dispatchers.IO) {
                if (name.trim() != playlist.title) YouTubeLibrary.rename(playlist.id, name)
                YouTubeLibrary.setPrivacy(playlist.id, if (public) YouTubeLibrary.PUBLIC else YouTubeLibrary.PRIVATE)
            }
        } catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth); throw auth }
        youTubeRows[playlist.id]?.let { youTubeRows[playlist.id] = it.copy(title = name.trim(), privacy = if (public) YouTubeLibrary.PUBLIC else YouTubeLibrary.PRIVATE) }
        val renamed = playlist.copy(title = name.trim())
        ui.register(renamed)
        installYouTubePlaylists(mutableYouTubePlaylists.value.map { if (it.id == playlist.id) it.copy(title = renamed.title) else it })
        mutableDetails.value[playlist.id]?.content?.let { installYouTubeDetail(it.copy(item = it.item.copy(title = renamed.title))) }
    }

    init {
        if (mutableGuest.value) mutableLibrary.value = LibraryUi(emptyLibrary())
        viewModelScope.launch {
            localPlaylistStore.playlists.collect { playlists ->
                playlists.forEach { ui.register(it.asMusic()) }
                val known = mutableDetails.value.keys.filter { it.startsWith(LOCAL_PLAYLIST_PREFIX) }
                known.forEach(::installLocalPlaylist)
            }
        }
        refreshLocalTracks()
        refreshYouTubeLibrary()
        viewModelScope.launch {
            // One history entry each time a queue position starts playing.
            var counted: String? = null
            player.state.collect { playback ->
                val track = playback.track
                val position = track?.let { "${playback.index}:${it.id}" }
                if (position == null) counted = null
                else if (playback.playing && position != counted) {
                    counted = position
                    withContext(Dispatchers.IO) { listeningHistory.record(track) }
                }
                if (track != null && playback.playing && mutableRecent.value.firstOrNull()?.id != track.id) {
                    mutableRecent.update { (listOf(track) + it.filterNot { previous -> previous.id == track.id }).take(12) }
                }
            }
        }
        viewModelScope.launch {
            var wasOnline = online.value
            online.collect { now ->
                if (now && !wasOnline) refreshYouTubeLibrary()
                if (now && !wasOnline && collections != null) {
                    sendPendingFavorites()
                    val age = withContext(Dispatchers.IO) { store.libraryAgeMs() }
                    if (age > LIBRARY_TTL_MS) refreshLibrary()
                }
                wasOnline = now
            }
        }
        accountJob = viewModelScope.launch {
            mutableAccount.value = AccountUi(working = true, restoring = true)
            try {
                val saved = withContext(Dispatchers.IO) {
                    vault.removeLegacySession()
                    runCatching { vault.load() }.getOrNull()
                }
                if (saved != null) {
                    // Activity recreation must keep the session that owns active audio handles.
                    SpotifyAccount.restore(app)?.let(::finishAccount)
                }
                mutableAccount.value = AccountUi()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showAccountError(failure)
            }
        }
    }

    fun toggleFavorite(item: Music) {
        if (item.isLocalTrack || item.isLocalPlaylist || item.source != fr.spotifeur.data.MusicSource.Spotify || item.id.startsWith("ytm:")) return
        if (item.kind != MusicKind.Track && item.kind != MusicKind.Album && item.kind != MusicKind.Artist) {
            mutableMessage.value = "This item cannot be changed in your favorites."
            return
        }
        val source = collections ?: return
        if (!pendingMutations.add(item.id)) return
        val saved = item.id !in savedIds.value
        changeFavorite(FavoriteChange(item, saved))
        viewModelScope.launch {
            try { sendFavorite(source, item, saved) } finally { pendingMutations.remove(item.id) }
        }
    }

    private fun changeFavorite(change: FavoriteChange) {
        favoriteChanges[change.item.id] = change
        savePendingFavorites()
        val current = mutableLibrary.value
        if (current.content != null) installLibrary(current.content, current.loading)
        else savedIds.update { if (change.saved) it + change.item.id else it - change.item.id }
    }

    /** Refused by the service: undone. Not reached: kept and sent with the next connection. */
    private suspend fun sendFavorite(source: SpotifyLibrary, item: Music, saved: Boolean) {
        try {
            source.setSaved(item, saved)
            if (favoriteChanges[item.id]?.saved == saved) {
                favoriteChanges[item.id] = FavoriteChange(item, saved, System.currentTimeMillis())
            }
            savePendingFavorites()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (auth: SpotifyAuthRequired) { accountLost(auth) }
        catch (failure: SpotifyFailure) {
            if (failure.status == null) return
            favoriteChanges.remove(item.id)
            savePendingFavorites()
            val current = mutableLibrary.value
            savedIds.update { if (saved) it - item.id else it + item.id }
            if (current.content != null) installLibrary(current.content, current.loading)
            mutableMessage.value = readable(failure)
        } catch (failure: IOException) {
            // Kept for the next connection.
            android.util.Log.i("SpotifeurLibrary", "Favorite kept for later: ${failure.javaClass.simpleName}")
        }
    }

    private fun savePendingFavorites() {
        val pending = favoriteChanges.values.filter { it.confirmedAtMs == null }
        preferences.edit {
            putString(KEY_PENDING_LIKES, encodePins(pending.filter { it.saved }.map { it.item }))
            putString(KEY_PENDING_UNLIKES, encodePins(pending.filterNot { it.saved }.map { it.item }))
        }
    }

    private fun loadPendingFavorites() {
        decodePins(preferences.getString(KEY_PENDING_LIKES, null)).forEach { favoriteChanges[it.id] = FavoriteChange(it, true) }
        decodePins(preferences.getString(KEY_PENDING_UNLIKES, null)).forEach { favoriteChanges[it.id] = FavoriteChange(it, false) }
    }

    private fun sendPendingFavorites() {
        val source = collections ?: return
        favoriteChanges.values.filter { it.confirmedAtMs == null }.forEach { change ->
            if (pendingMutations.add(change.item.id)) viewModelScope.launch {
                try { sendFavorite(source, change.item, change.saved) } finally { pendingMutations.remove(change.item.id) }
            }
        }
    }

    fun refreshLibrary() {
        refreshYouTubeLibrary()
        val source = collections ?: return
        if (!online.value && mutableLibrary.value.content != null) return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(loading = true, error = null) }
            val previous = mutableLibrary.value.content
            try {
                val result = source.load(previous) { installLibrary(it, true) }
                installLibrary(result, false)
                saveLibrary()
                withContext(BackgroundRequests) { completeSavedTracks(source) }
                keepCatalogAvailable()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { accountLost(auth) }
            catch (failure: Exception) {
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("library", "${failure.javaClass.simpleName} \"${readable(failure)}\"")
                // What was last read stays on screen, without an error for what the connection prevented.
                mutableLibrary.update { it.copy(loading = false, error = if (it.content == null) readable(failure) else null) }
            }
        }
    }
    private fun saveLibrary() {
        val content = mutableLibrary.value.content ?: return
        viewModelScope.launch(Dispatchers.IO) { store.saveLibrary(content) }
    }
    /** Fetches, page by page, the metadata of liked songs this device does not have yet. */
    private suspend fun completeSavedTracks(source: SpotifyLibrary) {
        var pages = 0
        while (online.value) {
            val content = mutableLibrary.value.content ?: return
            if (content.savedTracksLoaded >= content.savedTrackUris.size) break
            val before = content.savedTracksLoaded
            val next = source.loadMore(content)
            if (next.savedTracksLoaded == before) break
            installLibrary(next, false)
            if (++pages % SAVE_EVERY_PAGES == 0) saveLibrary()
            delay(BACKGROUND_REQUEST_GAP_MS)
        }
        if (pages > 0) saveLibrary()
    }
    /**
     * Keeps every page of the library readable without network, a little at a time. Pages come back
     * with what the device already knows, so only what actually changed costs a metadata request.
     */
    private fun keepCatalogAvailable() {
        val catalogSource = catalog ?: return
        offlineSyncJob?.cancel()
        offlineSyncJob = viewModelScope.launch(BackgroundRequests) {
            val content = mutableLibrary.value.content ?: return@launch
            val pinned = pins.value.filter { it.id.startsWith("spotify:") && it.id != LIKED_SONGS && it.kind != MusicKind.Track }
            val items = (pinned + content.playlists + content.albums + content.artists).distinctBy(Music::id).take(MAX_BACKGROUND_PAGES)
            items.forEach { item ->
                if (!online.value) return@launch
                val age = withContext(Dispatchers.IO) { store.detailAgeMs(item.id) }
                if (age < revalidateAfterMs(item) || detailJobs[item.id]?.isActive == true) return@forEach
                try {
                    val known = withContext(Dispatchers.IO) { store.loadDetail(item.id) }
                    known?.let { catalogSource.seed(it.tracks + it.related) }
                    val full = fullDetail(catalogSource, item)
                    withContext(Dispatchers.IO) { store.saveDetail(item.id, full) }
                    rememberCover(full)
                    keepCovers(full)
                    delay(BACKGROUND_REQUEST_GAP_MS)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { /* The next start tries again. */ }
            }
        }
    }
    /** On an unmetered network, the covers a stored page shows, so it looks the same without network. */
    private fun keepCovers(detail: CatalogDetail) {
        val network = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (network.isActiveNetworkMetered || coversRequested >= MAX_BACKGROUND_COVERS) return
        val loader = coil.Coil.imageLoader(app)
        (listOf(detail.item) + detail.tracks.take(COVERS_PER_PAGE)).mapNotNull(Music::imageUrl).distinct()
            .filter(requestedCovers::add).forEach { url ->
                if (coversRequested++ < MAX_BACKGROUND_COVERS) loader.enqueue(
                    coil.request.ImageRequest.Builder(app).data(url).memoryCachePolicy(coil.request.CachePolicy.DISABLED).build(),
                )
            }
    }
    private val requestedCovers = HashSet<String>()
    private var coversRequested = 0
    private suspend fun fullDetail(source: SpotifyCatalog, item: Music, atLeast: Int = Int.MAX_VALUE, pageLimit: Int = MAX_DETAIL_PAGES): CatalogDetail {
        var detail = source.detail(item)
        var pages = 1
        val cursors = HashSet<String>()
        while (detail.nextPage != null && detail.tracks.size < atLeast && pages < pageLimit) {
            val cursor = checkNotNull(detail.nextPage)
            if (!cursors.add(cursor)) throw SpotifyFailure("Spotify returned a repeated page. Try again.")
            val next = source.detail(item, cursor)
            detail = next.copy(tracks = detail.tracks + next.tracks)
            pages++
        }
        return detail
    }
    private fun revalidateAfterMs(item: Music): Long = when (item.kind) {
        MusicKind.Playlist, MusicKind.Radio -> PLAYLIST_TTL_MS
        MusicKind.Artist -> ARTIST_TTL_MS
        else -> ALBUM_TTL_MS
    }
    private fun installLibrary(content: HomeLibrary, loading: Boolean) {
        val previous = mutableLibrary.value.content
        val merged = if (loading && content.savedTracksLoaded == 0 && previous != null) content.copy(
            albums = previous.albums, artists = previous.artists, savedTracks = previous.savedTracks,
            savedTrackCount = previous.savedTrackCount, savedTrackUris = previous.savedTrackUris,
            savedTracksLoaded = previous.savedTracksLoaded,
        ) else content
        // A read can predate a like: confirmed changes stay on top for as long as the service may lag.
        val now = System.currentTimeMillis()
        favoriteChanges.values.removeAll { it.confirmedAtMs != null && now - it.confirmedAtMs > FAVORITE_LAG_MS }
        val visible = applyFavoriteChanges(merged, favoriteChanges.values).let { library ->
            library.copy(playlists = library.playlists.map { playlist ->
                val mosaic = playlistCovers[playlist.id]
                when {
                    playlist.imageUrl == null -> mosaic?.let { playlist.copy(imageUrl = it) } ?: playlist.also { coverFromStoredPage(it.id) }
                    mosaic != null && playlist.imageUrl != mosaic -> {
                        // The playlist got a picture of its own.
                        playlistCovers.remove(playlist.id); saveCovers(); playlist
                    }
                    else -> playlist
                }
            })
        }
        val warning = visible.warning.takeIf { online.value && visible.playlists.isEmpty() }
        mutableLibrary.value = LibraryUi(visible, loading, warning)
        savedIds.value = (visible.savedTrackUris + (visible.albums + visible.artists + visible.playlists).map(Music::id)).toSet()
        val liked = mutableDetails.value[LIKED_SONGS]?.content
        if (liked != null) mutableDetails.update { it + (LIKED_SONGS to DetailUi(liked.copy(
            tracks = visible.savedTracks,
            nextPage = visible.savedTracksLoaded.takeIf { size -> size < visible.savedTrackUris.size }?.toString(),
            total = visible.savedTrackCount,
        ))) }
    }
    fun loadMoreSavedTracks() {
        val source = collections ?: return
        val current = mutableLibrary.value.content ?: return
        if (mutableLibrary.value.loading || current.savedTracksLoaded >= current.savedTrackUris.size) return
        libraryJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(loading = true, error = null) }
            try { installLibrary(source.loadMore(current), false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableLibrary.update { it.copy(loading = false, error = readable(failure)) } }
        }
    }
    fun loadDetail(item: Music, refresh: Boolean = false, more: Boolean = false) {
        if (item.isLocalPlaylist) { installLocalPlaylist(item.id); return }
        if (item.id.startsWith(YTM_PLAYLIST_PREFIX)) { loadYouTubePlaylist(item, refresh || more); return }
        if (item.id.startsWith(fr.spotifeur.data.youtube.YTM_ALBUM_PREFIX) || item.id.startsWith(fr.spotifeur.data.youtube.YTM_ARTIST_PREFIX)) {
            loadYouTubePage(item, refresh); return
        }
        val source = catalog ?: return
        val shown = mutableDetails.value[item.id]
        if (!more && !refresh && shown?.content != null && shown.error == null) return
        if (shown?.loading == true && !refresh) return
        detailJobs[item.id]?.cancel()
        detailJobs[item.id] = viewModelScope.launch {
            mutableDetails.update { it + (item.id to (shown ?: DetailUi()).copy(loading = true, error = null)) }
            // What the device knows is on screen at once; the service is only asked when it is old.
            var stored: CatalogDetail? = null
            if (shown?.content == null && item.id != LIKED_SONGS) {
                stored = withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.let { page ->
                    // Pages stored before mosaics took the first track's cover for a playlist without picture.
                    val bare = mutableLibrary.value.content?.playlists?.firstOrNull { it.id == item.id }?.let { it.id in playlistCovers } == true
                    if (bare) page.copy(item = page.item.copy(imageUrl = playlistMosaic(page.tracks) ?: page.item.imageUrl)) else page
                }
                stored?.let {
                    source.seed(it.tracks + it.related)
                    mutableDetails.update { all -> all + (item.id to DetailUi(it, loading = true)) }
                }
            }
            val previous = stored?.let { DetailUi(it) } ?: shown
            if (!more && !refresh && item.id != LIKED_SONGS && previous?.content != null) {
                val age = withContext(Dispatchers.IO) { store.detailAgeMs(item.id) }
                // An artist page stored by the library sync has no profile yet: read it once opened.
                val incomplete = item.kind == MusicKind.Artist && previous.content.artist == null
                if (!online.value || (age < revalidateAfterMs(item) && !incomplete)) {
                    mutableDetails.update { it + (item.id to DetailUi(previous.content)) }
                    return@launch
                }
            }
            try {
                val page = if (more) previous?.content?.nextPage else null
                val result = if (item.id == LIKED_SONGS) {
                    val saved = mutableLibrary.value.content
                    val from = page?.toIntOrNull() ?: 0
                    val uris = saved?.savedTrackUris.orEmpty()
                    val tracks = if (from == 0) saved?.savedTracks.orEmpty() else source.hydrate(uris.drop(from).take(100))
                    val next = if (from == 0) saved?.savedTracksLoaded ?: tracks.size else from + 100
                    CatalogDetail(item.copy(note = "${saved?.savedTrackCount ?: 0} saved tracks"), tracks,
                        nextPage = next.takeIf { it < uris.size }?.toString(), total = saved?.savedTrackCount ?: 0)
                } else if (more) source.detail(item, page)
                // Revalidating must not shorten what was already read in several pages.
                else fullDetail(source, item, atLeast = previous?.content?.tracks?.size ?: 0)
                val merged = if (more && previous?.content != null) result.copy(tracks = previous.content.tracks + result.tracks) else result
                mutableDetails.update { it + (item.id to DetailUi(merged)) }
                rememberCover(merged)
                if (item.id != LIKED_SONGS) withContext(Dispatchers.IO) { store.saveDetail(item.id, merged) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { accountLost(auth) }
            catch (failure: Exception) {
                val known = previous?.content
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("catalog",
                    "${item.kind.name.lowercase()} page=${if (more) "next" else "first"} stored=${known != null} ${failure.javaClass.simpleName} \"${readable(failure)}\"")
                // Known pages stay readable; only what cannot be shown at all is an error.
                mutableDetails.update { it + (item.id to if (known != null) DetailUi(known) else DetailUi(error = readable(failure))) }
            }
        }
    }
    private fun installLocalPlaylist(id: String) {
        val playlist = localPlaylistStore.get(id) ?: return
        val music = playlist.asMusic()
        ui.register(music)
        mutableDetails.update { it + (id to DetailUi(CatalogDetail(music, playlist.tracks))) }
    }
    fun loadRadio(seed: Music, open: (Music) -> Unit) {
        val source = radio ?: return
        viewModelScope.launch {
            try {
                val result = source.station(seed)
                mutableDetails.update { it + (result.item.id to DetailUi(result)) }
                open(result.item)
                play(result.tracks, 0)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableMessage.value = readable(failure) }
        }
    }
    /** Album and artists of a track, read through the catalogue contract used by detail pages. */
    /**
     * The album and artists of a track. Spotify tracks use Spotify's pages when an account is signed in;
     * YouTube Music tracks, and Spotify tracks without an account, use YouTube Music's.
     */
    suspend fun trackRelations(track: Music): List<Music> {
        if (track.isLocalTrack) return emptyList()
        if (track.source == fr.spotifeur.data.MusicSource.Spotify && catalog != null) return detailForActions(track)?.related.orEmpty()
        if (!online.value) return emptyList()
        val videoId = track.youTubeVideoId ?: withContext(Dispatchers.IO) {
            fr.spotifeur.data.youtube.TrackMatcher.resolve(fr.spotifeur.data.youtube.MatchQuery(
                key = track.id, title = track.title, artists = track.subtitle, durationMs = track.durationMs, album = track.album,
            ))
        } ?: return emptyList()
        val found = withContext(Dispatchers.IO) { fr.spotifeur.data.youtube.YouTubePages.relations(videoId, track.title, track.subtitle) }
            ?: return emptyList()
        return listOfNotNull(found.album) + found.artists
    }
    /** Menu actions read the stored page first, so they work the same without network. */
    private suspend fun detailForActions(item: Music): CatalogDetail? {
        mutableDetails.value[item.id]?.content?.let { return it }
        withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.let { return it }
        val fetched = when {
            item.id.startsWith(fr.spotifeur.data.youtube.YTM_ALBUM_PREFIX) -> withContext(Dispatchers.IO) {
                fr.spotifeur.data.youtube.YouTubePages.album(item.id.removePrefix(fr.spotifeur.data.youtube.YTM_ALBUM_PREFIX))
            }
            item.id.startsWith(fr.spotifeur.data.youtube.YTM_ARTIST_PREFIX) -> withContext(Dispatchers.IO) {
                fr.spotifeur.data.youtube.YouTubePages.artist(item.id.removePrefix(fr.spotifeur.data.youtube.YTM_ARTIST_PREFIX))
            }
            else -> catalog?.detail(item)
        } ?: return null
        withContext(Dispatchers.IO) { store.saveDetail(item.id, fetched) }
        return fetched
    }
    internal val spotifyPlaylists = SpotifyPlaylists(app)
    internal suspend fun createSpotifyPlaylist(name: String, tracks: List<Music>, published: Boolean): Music {
        val created = spotifyPlaylists.create(name, tracks, published)
        ui.register(created)
        val current = mutableLibrary.value.content
        if (current != null) installLibrary(current.copy(playlists = listOf(created) + current.playlists.filterNot { it.id == created.id }), false)
        mutableDetails.update { it + (created.id to DetailUi(CatalogDetail(created, tracks, total = tracks.size))) }
        withContext(Dispatchers.IO) { store.saveDetail(created.id, CatalogDetail(created, tracks, total = tracks.size)) }
        return created
    }
    internal fun spotifyPlaylistChanged(playlist: Music) {
        ui.register(playlist)
        mutableLibrary.value.content?.let { content ->
            installLibrary(content.copy(playlists = content.playlists.map { if (it.id == playlist.id) playlist else it }), false)
        }
        loadDetail(playlist, refresh = true)
    }
    internal suspend fun playlistSuggestions(playlist: Music, tracks: List<Music>): List<Music> =
        spotifyPlaylists.recommendations(playlist.id, tracks, mutableLibrary.value.content?.savedTracks.orEmpty())

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal suspend fun addSpotifyPlaylistTracks(playlist: Music, tracks: List<Music>) {
        val before = tracksOf(playlist)
        spotifyPlaylists.add(playlist.id, tracks) {
            detailJobs.remove(playlist.id)?.cancel()
            val content = mutableDetails.value[playlist.id]?.content ?: CatalogDetail(playlist, before)
            val updated = content.copy(tracks = before + tracks, total = content.total + tracks.size, nextPage = null)
            mutableDetails.update { it + (playlist.id to DetailUi(updated)) }
            withContext(Dispatchers.IO) { store.saveDetail(playlist.id, updated) }
            if (fr.spotifeur.playback.DownloadCenter.collections.value.containsKey(playlist.id)) {
                fr.spotifeur.playback.DownloadCenter.enqueue(app, updated.item, updated.tracks)
            }
        }
    }

    /** Spotifeur's plays merged with the tracks Spotify reports for the account's other apps and devices. */
    internal suspend fun fullListeningHistory(): List<fr.spotifeur.data.HistoryEntry> {
        withContext(Dispatchers.IO) { listeningHistory.load() }
        val local = listeningHistory.entries.value
        val source = catalog ?: return local
        if (!online.value) return local
        val remote = runCatching {
            val plays = fr.spotifeur.data.spotifyRecentPlays(SpotifyAccount.require(app))
            val tracks = source.hydrate(plays.map { it.track }).associateBy(Music::id)
            plays.mapNotNull { play -> tracks[play.track]?.let { fr.spotifeur.data.HistoryEntry(it, play.playedAt, fromSpotify = true) } }
        }.getOrElse { failure -> if (failure is kotlinx.coroutines.CancellationException) throw failure; emptyList() }
        return fr.spotifeur.data.mergeHistory(local, remote)
    }

    /** Removes the track at [index] of the playlist as loaded, leaving other copies of it in place. */
    internal suspend fun removeSpotifyPlaylistTrack(playlist: Music, index: Int) {
        val content = mutableDetails.value[playlist.id]?.content ?: return
        val track = content.tracks.getOrNull(index) ?: return
        val occurrence = content.tracks.take(index).count { it.id == track.id }
        spotifyPlaylists.remove(playlist.id, track.id, occurrence) {
            detailJobs.remove(playlist.id)?.cancel()
            val latest = mutableDetails.value[playlist.id]?.content ?: content
            val position = if (latest.tracks.getOrNull(index)?.id == track.id) index
                else latest.tracks.withIndex().filter { it.value.id == track.id }.getOrNull(occurrence)?.index ?: return@remove
            // The next page starts at a row offset, which moves back by the removed row.
            val updated = latest.copy(tracks = latest.tracks.toMutableList().apply { removeAt(position) }, total = (latest.total - 1).coerceAtLeast(0),
                nextPage = latest.nextPage?.toIntOrNull()?.let { (it - 1).coerceAtLeast(0).toString() } ?: latest.nextPage)
            mutableDetails.update { it + (playlist.id to DetailUi(updated)) }
            withContext(Dispatchers.IO) { store.saveDetail(playlist.id, updated) }
        }
    }

    /** Downloads follow the account's own right to keep music offline. */
    suspend fun offlineAllowed(): Boolean {
        val session = SpotifyAccount.require(getApplication())
        val offline = spotifyAttribute(transport, session, "offline")
        return offline == null && spotifyAttribute(transport, session, "type") == "premium" || offline == "true" || offline == "1"
    }
    private var lyrics: SpotifyLyricsSource? = null
    fun lyricsSource(): SpotifyLyricsSource? = lyrics
    fun showMessage(text: String) { mutableMessage.value = text }
    suspend fun tracksOf(item: Music): List<Music> { return when {
        item.id == LIKED_SONGS -> {
            if (online.value) collections?.let { completeSavedTracks(it) }
            mutableLibrary.value.content?.savedTracks.orEmpty()
        }
        item.isLocalPlaylist -> withLocalFiles(localPlaylistStore.get(item.id)?.tracks.orEmpty())
        item.id.startsWith(YTM_PLAYLIST_PREFIX) -> youTubeTracks(item)
        item.isLocalTrack || item.kind == MusicKind.Track -> withLocalFiles(listOf(item))
        else -> {
            val known = detailForActions(item) ?: return emptyList()
            withLocalFiles(if (known.nextPage == null || !online.value) known.tracks
            else {
                val source = catalog ?: return withLocalFiles(known.tracks)
                val complete = fullDetail(source, item, pageLimit = Int.MAX_VALUE)
                mutableDetails.update { it + (item.id to DetailUi(complete)) }
                withContext(Dispatchers.IO) { store.saveDetail(item.id, complete) }
                complete.tracks
            })
        }
    }
    }
    /** Without a Spotify account, Spotify tracks play through their YouTube Music match. */
    fun play(queue: List<Music>, index: Int = 0) {
        val tracks = withLocalFiles(queue)
        val selected = tracks.getOrNull(index)
        if (selected != null && selected.isLocalTrack && selected.mediaUri == null) {
            mutableMessage.value = app.getString(fr.spotifeur.R.string.local_file_missing, selected.title); return
        }
        player.play(tracks, index)
    }

    /** Local file references with the device file they point to, when there is one. */
    fun withLocalFiles(tracks: List<Music>): List<Music> {
        if (tracks.none { it.id.startsWith(fr.spotifeur.data.SPOTIFY_LOCAL_PREFIX) || (it.isLocalTrack && it.mediaUri == null) }) return tracks
        val files = mutableLocalTracks.value
        val byId = files.associateBy(Music::id)
        val index = localFileIndex(files)
        return tracks.map { track ->
            when {
                track.mediaUri != null -> track
                track.id.startsWith(fr.spotifeur.data.LOCAL_TRACK_PREFIX) -> byId[track.id] ?: index.match(track)
                    ?.let { track.copy(mediaUri = it.mediaUri, imageUrl = track.imageUrl ?: it.imageUrl) } ?: track
                else -> fr.spotifeur.data.withLocalFile(track, index)
            }
        }
    }
    @Volatile private var cachedFileIndex: fr.spotifeur.data.LocalFileIndex? = null
    /** Built once per list of device files, reused by every playlist and queue. */
    private fun localFileIndex(files: List<Music>): fr.spotifeur.data.LocalFileIndex =
        cachedFileIndex?.takeIf { it.files === files } ?: fr.spotifeur.data.LocalFileIndex(files).also { cachedFileIndex = it }
    fun consumeMessage() { mutableMessage.value = null }

    fun search(query: String, debounce: Boolean = true) {
        searchJob?.cancel()
        val trimmed = query.trim()
        // What is on screen stays until the first source answers for the new text: no blank list while typing.
        mutableDiscovery.update {
            it.copy(query = query, results = if (trimmed.length < 2) emptyList() else it.results,
                nextPage = null, youTubeNext = null, error = null, loading = trimmed.length >= 2)
        }
        if (trimmed.length < 2) return
        if (!online.value || discovery.value.activeSource == SearchSource.Local) { showLocalResults(trimmed, null); return }
        loadResults(trimmed, debounce)
    }

    fun setSearchSource(source: SearchSource) {
        if (discovery.value.source == source) return
        mutableDiscovery.update { it.copy(source = source) }
        search(discovery.value.query, debounce = false)
    }

    /** Device files whose title, artists or album hold every word of [query]. */
    private fun matchingDeviceFiles(query: String): List<Music> =
        mutableLocalTracks.value.filter { fr.spotifeur.utils.matchesWords(query, it.title, it.subtitle, it.album) }.take(MAX_LOCAL_RESULTS)

    /**
     * First page: every source of the active choice at once. Each one is shown as soon as it answers,
     * merged with what is already there; a failing one leaves the others.
     */
    private fun loadResults(query: String, debounce: Boolean) {
        val choice = discovery.value.activeSource
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val started = android.os.SystemClock.elapsedRealtime()
            val spotify = remote.takeIf { choice == SearchSource.All || choice == SearchSource.Spotify }
            val withYouTube = choice == SearchSource.All || choice == SearchSource.YouTubeMusic
            val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
            var spotifyItems: List<Music>? = null
            var youTubeMixed: List<Music>? = null
            var youTubeSongs: List<Music>? = null
            val device = if (choice == SearchSource.All) matchingDeviceFiles(query) else emptyList()
            val failures = ArrayList<Throwable>()
            var pending = (if (spotify != null) 1 else 0) + (if (withYouTube) 2 else 0)
            fun publish() {
                val youTube = (youTubeMixed.orEmpty() + youTubeSongs.orEmpty()).distinctBy(Music::id)
                val results = if (choice == SearchSource.Spotify) spotifyItems.orEmpty()
                    else fr.spotifeur.data.SearchRanking.merge(query, listOfNotNull(
                        spotifyItems?.let { fr.spotifeur.data.SearchRanking.Source(it, 1.0) },
                        fr.spotifeur.data.SearchRanking.Source(youTube, if (spotify != null) 0.97 else 1.0),
                        fr.spotifeur.data.SearchRanking.Source(device, 1.02, deduplicated = false),
                    ))
                val finished = pending == 0
                if (finished && results.isEmpty() && failures.isNotEmpty()) { showLocalResults(query, readableSearchFailure(failures.first())); return }
                if (!finished && results.isEmpty()) return
                mutableDiscovery.update { it.copy(loading = !finished, results = results, error = null) }
            }
            if (device.isNotEmpty()) publish()
            // Each source writes on the main thread, so the merges never overlap.
            fun <T> source(name: String, read: suspend () -> T, keep: (T) -> Unit) = launch {
                val result = try { Result.success(read()) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Result.failure(failure) }
                android.util.Log.d("SpotifeurSearch", "$name ${android.os.SystemClock.elapsedRealtime() - started} ms ${result.exceptionOrNull()?.let { "${it.javaClass.simpleName} ${it.message}" } ?: "ok"}")
                result.onSuccess(keep).onFailure { failures += it }
                pending--
                publish()
            }
            if (spotify != null) source("spotify", { spotify.search(query, locale, null) }) { page ->
                spotifyItems = page.items
                mutableDiscovery.update { it.copy(nextPage = page.nextToken) }
            }
            if (withYouTube) {
                source("youtube-mixed", { withContext(Dispatchers.IO) { fr.spotifeur.data.youtube.YouTubePages.searchAll(query) } }) { youTubeMixed = it }
                source("youtube-songs", { withContext(Dispatchers.IO) { fr.spotifeur.data.youtube.YouTubeMusic.searchSongsPage(query) } }) { page ->
                    youTubeSongs = page.tracks
                    mutableDiscovery.update { it.copy(youTubeNext = page.next) }
                }
            }
        }
    }

    private fun readableSearchFailure(failure: Throwable): String = when (failure) {
        is fr.spotifeur.data.youtube.YouTubeFailure -> app.getString(fr.spotifeur.R.string.youtube_search_failed)
        is SpotifyFailure -> failure.message.orEmpty()
        is InvalidProtocolBufferException -> "Unexpected Spotify response format."
        else -> "The search was interrupted."
    }

    /** Next pages are added after what is shown, without moving it. */
    fun searchNext() {
        val state = discovery.value
        if (state.loading || !state.hasMore) return
        val query = state.query.trim()
        val spotifyToken = state.nextPage
        val youTubeToken = state.youTubeNext
        mutableDiscovery.update { it.copy(loading = true, error = null) }
        searchJob = viewModelScope.launch {
            val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
            val spotify = async { spotifyToken?.let { token -> remote?.let { source -> runCatching { source.search(query, locale, token) } } } }
            val youTube = async(Dispatchers.IO) { youTubeToken?.let { token -> runCatching { fr.spotifeur.data.youtube.YouTubeMusic.searchMore(token) } } }
            val spotifyPage = spotify.await()
            val youTubePage = youTube.await()
            val added = fr.spotifeur.data.SearchRanking.merge(query, listOfNotNull(
                spotifyPage?.getOrNull()?.items?.let { fr.spotifeur.data.SearchRanking.Source(it) },
                youTubePage?.getOrNull()?.tracks?.let { fr.spotifeur.data.SearchRanking.Source(it, 0.97) },
            ))
            val failed = listOfNotNull(spotifyPage?.exceptionOrNull(), youTubePage?.exceptionOrNull()).firstOrNull()
            mutableDiscovery.update {
                it.copy(loading = false, results = (it.results + added).distinctBy(Music::id),
                    nextPage = spotifyPage?.getOrNull()?.nextToken?.takeUnless { next -> next == spotifyToken },
                    youTubeNext = youTubePage?.getOrNull()?.next?.takeUnless { next -> next == youTubeToken },
                    error = failed?.takeIf { _ -> added.isEmpty() }?.let(::readableSearchFailure))
            }
        }
    }

    /** Without the service, search what the device holds: the library, its pins and listened tracks. */
    private fun showLocalResults(query: String, failure: String?) {
        searchJob = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                val content = mutableLibrary.value.content
                val listened = fr.spotifeur.data.AudioIndex.get(app).all().map { it.track }.filter { it.title.isNotEmpty() }
                val candidates = pins.value + content?.playlists.orEmpty() + content?.albums.orEmpty() +
                    content?.artists.orEmpty() + content?.savedTracks.orEmpty() + listened +
                    localPlaylistStore.playlists.value.map { it.asMusic() } + mutableLocalTracks.value
                val wanted = query.lowercase()
                candidates.filter { it.title.lowercase().contains(wanted) || it.subtitle.lowercase().contains(wanted) }
                    .distinctBy(Music::id).take(MAX_LOCAL_RESULTS)
            }
            mutableDiscovery.update {
                it.copy(loading = false, results = found, nextPage = null, error = failure.takeIf { found.isEmpty() })
            }
        }
    }

    fun connectSpotify(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) return
        connect { session -> session.connect(username.trim(), password, ::requestCode) }
    }

    fun connectSpotifyEmail(address: String) {
        val email = address.trim()
        if (email.isBlank()) return
        connect { session -> session.connectEmail(email, loginFlowId) { requestCode(it, email) } }
    }

    private fun connect(authenticate: suspend (SpotifySession) -> Unit) {
        if (mutableAccount.value.working || remote != null) return
        accountJob = viewModelScope.launch {
            mutableAccount.value = AccountUi(working = true)
            try {
                val session = SpotifySession(app, transport, vault, deviceId)
                authenticate(session)
                finishAccount(session)
                mutableAccount.value = AccountUi()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showAccountError(failure)
            }
        }
    }

    private suspend fun requestCode(length: Int, email: String? = null): String {
        val deferred = CompletableDeferred<String>()
        pendingCode = deferred
        mutableAccount.value = AccountUi(working = true, codeLength = length, codeEmail = email)
        return try {
            deferred.await().also { mutableAccount.value = AccountUi(working = true) }
        } finally {
            if (pendingCode === deferred) pendingCode = null
        }
    }

    fun submitCode(code: String) {
        if (code.length == mutableAccount.value.codeLength && code.all { it in '0'..'9' }) {
            pendingCode?.complete(code)
        }
    }

    private fun finishAccount(session: SpotifySession) {
        SpotifyAccount.attach(session)
        // A guest who signs in keeps the multi-service playlists; the library now comes from Spotify.
        if (mutableLibrary.value.content?.let { it.playlists.isEmpty() && it.savedTrackCount == 0 } == true) mutableLibrary.value = LibraryUi()
        catalog = SpotifyCatalog(transport, session) { uri, from, length, fallback -> spotifyPlaylists.page(uri, from, length, fallback) }
        collections = SpotifyLibrary(transport, session, requireNotNull(catalog))
        radio = SpotifyRadio(transport, session)
        remote = SpotifySearch(transport, session)
        lyrics = SpotifyLyricsSource(transport, session)
        mutableDiscovery.update { it.copy(spotifyEnabled = true) }
        search(discovery.value.query)
        viewModelScope.launch { openCatalog() }
    }

    /** The library on disk comes first. The service is asked afterwards, and only when it can add something. */
    private suspend fun openCatalog() {
        val stored = withContext(Dispatchers.IO) { store.loadLibrary() }
        loadPendingFavorites()
        // Covers for playlists without a picture, from pages already on the device.
        loadCovers()
        stored?.playlists?.let { playlists ->
            val covers = withContext(Dispatchers.IO) {
                playlists.mapNotNull { playlist ->
                    val detail = store.loadDetail(playlist.id) ?: return@mapNotNull null
                    // Earlier versions filled a playlist without picture with its first track's cover.
                    val firstCover = detail.tracks.firstNotNullOfOrNull(Music::imageUrl)
                    val bare = playlist.imageUrl == null || playlist.id in playlistCovers ||
                        (playlist.imageUrl == detail.item.imageUrl && playlist.imageUrl == firstCover)
                    if (bare) playlist.id to (playlistMosaic(detail.tracks) ?: firstCover) else null
                }
            }
            covers.forEach { (id, url) -> if (url != null) playlistCovers[id] = url }
            saveCovers()
        }
        if (stored != null) installLibrary(stored.copy(playlists = stored.playlists.map {
            // A stored cover filled in by an earlier start yields to the mosaic computed now.
            if (it.id in playlistCovers) it.copy(imageUrl = null) else it
        }), false)
        else if (favoriteChanges.isNotEmpty()) savedIds.update { ids ->
            favoriteChanges.values.fold(ids) { all, change -> if (change.saved) all + change.item.id else all - change.item.id }
        }
        sendPendingFavorites()
        val age = withContext(Dispatchers.IO) { store.libraryAgeMs() }
        when {
            stored == null -> refreshLibrary()
            online.value && (age > LIBRARY_TTL_MS || stored.playlists.any { it.ownerUsername == null }) -> refreshLibrary()
            online.value -> collections?.let { source ->
                libraryJob = viewModelScope.launch {
                    try { withContext(BackgroundRequests) { completeSavedTracks(source) }; keepCatalogAvailable() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (auth: SpotifyAuthRequired) { accountLost(auth) }
                    catch (_: IOException) { /* The next connection completes it. */ }
                }
            }
        }
    }

    /** Only a refusal of the saved account leads back to the sign-in screen. */
    private fun accountLost(failure: SpotifyAuthRequired) {
        disconnectSpotify()
        mutableAccount.value = AccountUi(error = failure.message)
    }

    private fun showAccountError(failure: Exception) {
        if (failure is SpotifyAuthRequired) vault.clear()
        mutableAccount.value = AccountUi(
            error = when (failure) {
                is SpotifyFailure -> failure.message
                is InvalidProtocolBufferException -> "Unexpected Android login response."
                is IOException -> "The connection to Spotify was interrupted."
                else -> "Could not open the Spotify session on this device."
            },
        )
    }

    fun disconnectSpotify() {
        accountJob?.cancel()
        pendingCode?.cancel()
        pendingCode = null
        searchJob?.cancel()
        libraryJob?.cancel()
        detailJobs.values.forEach { it.cancel() }; detailJobs.clear()
        offlineSyncJob?.cancel()
        favoriteChanges.clear(); savePendingFavorites()
        player.stop(); SpotifyAccount.clear()
        vault.clear()
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
        remote = null
        catalog = null; collections = null; radio = null; lyrics = null
        mutableLibrary.value = if (mutableGuest.value) LibraryUi(emptyLibrary()) else LibraryUi()
        mutableDetails.value = emptyMap()
        savedIds.value = emptySet(); mutableRecent.value = emptyList()
        mutableAccount.value = AccountUi()
        mutableDiscovery.update { it.copy(spotifyEnabled = false) }
        search(discovery.value.query)
    }

    /** A report for a bug ticket: entitlements, library sizes and recent failures, without identifiers. */
    suspend fun diagnosticReport(): String {
        val content = mutableLibrary.value.content
        val sizes = content?.let {
            "playlists=${it.playlists.size} albums=${it.albums.size} artists=${it.artists.size} savedTracks=${it.savedTrackCount}"
        } ?: "not loaded"
        return withContext(Dispatchers.IO) { fr.spotifeur.data.diagnostics.buildDiagnosticReport(app, sizes) }
    }
    fun clearDiagnostics() = fr.spotifeur.data.diagnostics.DiagnosticLog.clear()

    override fun onCleared() {
        spotifyPlaylists.close()
        player.close()
    }
    private fun readable(failure: Exception): String = when (failure) {
        is SpotifyFailure -> failure.message ?: "Spotify is unavailable. Try again."
        is InvalidProtocolBufferException -> "The Spotify response could not be read."
        is IOException -> "The connection to Spotify was interrupted. Try again."
        else -> "This operation could not be completed. Try again."
    }
    /** Tracks to add to a playlist, from one source; nothing is kept in the search history. */
    suspend fun searchTracks(query: String, source: String): List<Music> {
        val text = query.trim()
        if (text.length < 2) return emptyList()
        return when (source) {
            TRACKS_SPOTIFY -> {
                val search = remote ?: return emptyList()
                val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
                search.search(text, locale, null).items.filter { it.kind == MusicKind.Track }
            }
            TRACKS_YOUTUBE -> withContext(Dispatchers.IO) { fr.spotifeur.data.youtube.YouTubeMusic.search(text).tracks }
            else -> withContext(Dispatchers.Default) {
                val wanted = text.lowercase()
                mutableLocalTracks.value.filter { it.title.lowercase().contains(wanted) || it.subtitle.lowercase().contains(wanted) || it.album.lowercase().contains(wanted) }
            }
        }
    }

    /** Saves [jpeg] as the cover of a multi-service playlist, or removes the cover when null. */
    suspend fun setLocalPlaylistCover(id: String, jpeg: ByteArray?) {
        val directory = java.io.File(app.filesDir, "playlist-covers")
        val previous = localPlaylistStore.get(id)?.cover
        val cover = if (jpeg == null) null else withContext(Dispatchers.IO) {
            directory.mkdirs()
            java.io.File(directory, "${UUID.randomUUID()}.jpg").apply { writeBytes(jpeg) }.toURI().toString()
        }
        localPlaylistStore.setCover(id, cover)
        // A new address for every change, so cached thumbnails never show the old picture.
        previous?.takeIf { it.startsWith("file:") }?.let { old ->
            withContext(Dispatchers.IO) { runCatching { java.io.File(java.net.URI(old)).takeIf { it.parentFile == directory }?.delete() } }
        }
    }

    private fun emptyLibrary() = HomeLibrary(emptyList(), emptyList(), emptyList(), emptyList(), 0)

    // Playlists: deletion, import, export --------------------------------------------------------

    /** A multi-service playlist is removed from the device; a Spotify playlist from the account's library. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun deletePlaylist(item: Music) {
        if (item.isLocalPlaylist) {
            localPlaylistStore.delete(item.id)
            fr.spotifeur.playback.DownloadCenter.remove(app, item.id)
            pinnedStore.pins.value.firstOrNull { it.id == item.id }?.let(pinnedStore::toggle)
            mutableDetails.update { it - item.id }
            return
        }
        if (item.id.startsWith(YTM_PLAYLIST_PREFIX)) { deleteYouTubePlaylist(item); return }
        if (item.kind != MusicKind.Playlist || item.id == LIKED_SONGS || !item.id.startsWith("spotify:playlist:")) return
        viewModelScope.launch {
            try {
                spotifyPlaylists.unfollow(item.id)
                mutableLibrary.value.content?.let { content ->
                    installLibrary(content.copy(playlists = content.playlists.filterNot { it.id == item.id }), false)
                    saveLibrary()
                }
                pinnedStore.pins.value.firstOrNull { it.id == item.id }?.let(pinnedStore::toggle)
                fr.spotifeur.playback.DownloadCenter.remove(app, item.id)
                mutableDetails.update { it - item.id }
                mutableMessage.value = app.getString(fr.spotifeur.R.string.playlist_deleted, item.title)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { accountLost(auth) }
            catch (failure: Exception) { mutableMessage.value = (failure as? SpotifyFailure)?.message ?: readable(failure) }
        }
    }

    // Converter ----------------------------------------------------------------------------------

    private val mutableConversion = MutableStateFlow<fr.spotifeur.data.PlaylistConversion?>(null)
    /** The conversion shown by the converter, kept while its screen is open. */
    val conversion = mutableConversion.asStateFlow()

    /** Kinds a playlist can be converted to now: Spotify needs its account, YouTube Music a Google account. */
    fun conversionTargets(source: Music): Map<fr.spotifeur.data.PlaylistKind, Boolean> {
        val from = fr.spotifeur.data.playlistKindOf(source) ?: return emptyMap()
        return fr.spotifeur.data.PlaylistKind.entries.filter { it != from }.associateWith { kind ->
            when (kind) {
                fr.spotifeur.data.PlaylistKind.Spotify -> remote != null
                fr.spotifeur.data.PlaylistKind.YouTubeMusic -> YouTubeAccount.signedIn
                fr.spotifeur.data.PlaylistKind.MultiService -> true
            }
        }
    }

    fun startConversion(source: Music, target: fr.spotifeur.data.PlaylistKind) {
        val current = mutableConversion.value
        if (current != null && current.source.id == source.id && current.target == target && current.state.value.created == null) return
        current?.cancel()
        val conversion = fr.spotifeur.data.PlaylistConversion(
            scope = viewModelScope, source = source, target = target,
            loadTracks = { tracksOf(source) },
            search = conversionSearch(target),
            // A device file can stay in a Spotify playlist as Spotify's own local file reference.
            fallback = { track -> track.takeIf { target == fr.spotifeur.data.PlaylistKind.Spotify && it.id.startsWith(fr.spotifeur.data.LOCAL_TRACK_PREFIX) } },
            parallelism = if (target == fr.spotifeur.data.PlaylistKind.Spotify) 2 else 3,
        )
        mutableConversion.value = conversion
        conversion.start()
    }

    fun endConversion() {
        mutableConversion.value?.cancel()
        mutableConversion.value = null
    }

    private fun conversionSearch(target: fr.spotifeur.data.PlaylistKind) = fr.spotifeur.data.MatchSearch { track ->
        val text = fr.spotifeur.data.ConversionMatching.searchText(track)
        when (target) {
            fr.spotifeur.data.PlaylistKind.Spotify -> {
                val search = remote ?: throw SpotifyFailure("Sign in to Spotify first.")
                val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
                search.search(text, locale, null).items.filter { it.kind == MusicKind.Track }
            }
            fr.spotifeur.data.PlaylistKind.YouTubeMusic -> withContext(Dispatchers.IO) {
                // Songs first; videos only when no song is close, as for playback without a Spotify account.
                val songs = fr.spotifeur.data.youtube.YouTubeMusic.searchSongs(text)
                val best = fr.spotifeur.data.ConversionMatching.rank(track, songs).firstOrNull()?.second ?: 0.0
                if (best >= fr.spotifeur.data.ConversionMatching.CONFIDENT) songs
                else songs + runCatching { fr.spotifeur.data.youtube.YouTubeMusic.searchVideos(text) }.getOrDefault(emptyList())
            }
            fr.spotifeur.data.PlaylistKind.MultiService -> emptyList()
        }
    }

    /** Creates the converted playlist in its destination. */
    internal suspend fun createConverted(target: fr.spotifeur.data.PlaylistKind, name: String, tracks: List<Music>): Music = when (target) {
        fr.spotifeur.data.PlaylistKind.MultiService -> localPlaylistStore.create(name, tracks).asMusic().also { ui.register(it) }
        fr.spotifeur.data.PlaylistKind.YouTubeMusic -> createYouTubePlaylist(name, tracks, public = false)
        fr.spotifeur.data.PlaylistKind.Spotify -> {
            // One creation request stays small; the rest is added in batches.
            val created = createSpotifyPlaylist(name, tracks.take(100), published = false)
            if (tracks.size > 100) addSpotifyPlaylistTracks(created, tracks.drop(100))
            created
        }
    }

    /** The account's own playlist is deleted; a saved one only leaves the library. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun deleteYouTubePlaylist(item: Music) {
        if (!YouTubeAccount.signedIn) return
        val owned = youTubePlaylistOwned(item)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (owned) YouTubeLibrary.delete(item.id) else YouTubeLibrary.removeFromLibrary(item.id) }
                installYouTubePlaylists(mutableYouTubePlaylists.value.filterNot { it.id == item.id })
                markYouTubeEditable(item.id, false)
                youTubeRows.remove(item.id)
                pinnedStore.pins.value.firstOrNull { it.id == item.id }?.let(pinnedStore::toggle)
                fr.spotifeur.playback.DownloadCenter.remove(app, item.id)
                mutableDetails.update { it - item.id }
                withContext(Dispatchers.IO) { store.removeDetail(item.id) }
                mutableMessage.value = app.getString(fr.spotifeur.R.string.playlist_deleted, item.title)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: YouTubeAuthRequired) { youTubeAccountLost(auth) }
            catch (failure: Exception) { mutableMessage.value = failure.message ?: app.getString(fr.spotifeur.R.string.youtube_playlist_failed) }
        }
    }

    /** The playlist as CSV, with every track's source and reference. */
    suspend fun exportCsv(item: Music): String {
        val tracks = tracksOf(item)
        return fr.spotifeur.data.PlaylistCsv.export(item.title, tracks)
    }

    suspend fun importCsv(text: String, fileName: String): Music {
        val imported = withContext(Dispatchers.Default) { fr.spotifeur.data.PlaylistCsv.import(text, fileName.substringBeforeLast('.')) }
        if (imported.tracks.isEmpty()) throw IllegalArgumentException(app.getString(fr.spotifeur.R.string.import_no_tracks))
        return createImported(imported.name, completeSpotifyTracks(imported.tracks))
    }

    /** A YouTube, YouTube Music or Spotify playlist link, copied as a multi-service playlist. */
    suspend fun importLink(link: String): Music {
        fr.spotifeur.data.youtube.youTubePlaylistId(link)?.let { id ->
            val playlist = withContext(Dispatchers.IO) { fr.spotifeur.data.youtube.YouTubeMusic.playlist(id) }
            return createImported(playlist.title, playlist.tracks)
        }
        val spotifyId = fr.spotifeur.data.spotifyPlaylistId(link)
            ?: throw IllegalArgumentException(app.getString(fr.spotifeur.R.string.import_unknown_link))
        val source = catalog
        if (source != null) {
            val item = Music("spotify:playlist:$spotifyId", "", "", MusicKind.Playlist)
            val detail = fullDetail(source, item, pageLimit = Int.MAX_VALUE)
            return createImported(detail.item.title.ifBlank { "Spotify" }, detail.tracks)
        }
        val content = withContext(Dispatchers.IO) { fr.spotifeur.data.SpotifyPublicPlaylist.read(spotifyId) }
        if (content.tracks.isEmpty()) throw IllegalArgumentException(app.getString(fr.spotifeur.R.string.import_no_tracks))
        return createImported(content.name.ifBlank { "Spotify" }, content.tracks)
    }

    /** With an account, Spotify tracks read from a file get their covers and current metadata. */
    private suspend fun completeSpotifyTracks(tracks: List<Music>): List<Music> {
        val source = catalog ?: return tracks
        val ids = tracks.filter { it.id.startsWith("spotify:track:") && it.imageUrl == null }.map(Music::id)
        if (ids.isEmpty() || !online.value) return tracks
        val known = try { source.hydrate(ids).associateBy(Music::id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyMap() }
        return tracks.map { known[it.id] ?: it }
    }

    private fun createImported(name: String, tracks: List<Music>): Music {
        val playlist = localPlaylistStore.create(name.ifBlank { app.getString(fr.spotifeur.R.string.import_default_name) }, withLocalFiles(tracks))
        val music = playlist.asMusic()
        ui.register(music)
        mutableMessage.value = app.getString(fr.spotifeur.R.string.import_done, music.title, playlist.tracks.size)
        return music
    }

    companion object {
        const val LIKED_SONGS = "spotify:collection:tracks"
        private const val YOUTUBE_PAGE_TTL_MS = 24L * 60 * 60 * 1000
        private const val SEARCH_DEBOUNCE_MS = 250L
        const val TRACKS_SPOTIFY = "spotify"
        const val TRACKS_YOUTUBE = "youtube"
        const val TRACKS_FILES = "files"
        private const val KEY_GUEST = "guest"
        private const val KEY_PENDING_LIKES = "pending_likes"
        private const val KEY_PLAYLIST_COVERS = "playlist_covers"
        private const val KEY_PENDING_UNLIKES = "pending_unlikes"
        private const val KEY_YOUTUBE_PLAYLISTS = "youtube_playlists"
        private const val KEY_YOUTUBE_EDITABLE = "youtube_editable"
        private const val FAVORITE_LAG_MS = 2 * 60 * 1000L
        private const val LIBRARY_TTL_MS = 10 * 60 * 1000L
        private const val PLAYLIST_TTL_MS = 12 * 60 * 60 * 1000L
        private const val ARTIST_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val ALBUM_TTL_MS = 30 * 24 * 60 * 60 * 1000L
        /** Large libraries are read in the background for offline use; Spotify answers 429 to bursts. */
        private const val BACKGROUND_REQUEST_GAP_MS = 1_000L
        private const val SAVE_EVERY_PAGES = 5
        private const val MAX_BACKGROUND_PAGES = 400
        private const val MAX_DETAIL_PAGES = 30
        private const val MAX_LOCAL_RESULTS = 60
        private const val COVERS_PER_PAGE = 60
        private const val MAX_BACKGROUND_COVERS = 1500
    }
}
