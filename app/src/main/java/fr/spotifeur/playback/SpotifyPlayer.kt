package fr.spotifeur.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.SpotifyFailure
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Playback(
    val queue: List<Music> = emptyList(), val index: Int = 0, val playing: Boolean = false,
    val buffering: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF, val error: String? = null,
    val canNext: Boolean = false,
    val playWhenReady: Boolean = false,
    /** Measured from the selected audio track, for the file information of the player. */
    val audioFormat: String = "",
    /** Tracks the user queued, waiting right after the current one. */
    val queuedCount: Int = 0,
) { val track: Music? get() = queue.getOrNull(index) }

/** Outlives the activity, like the playback service whose queue it describes. */
private object ShuffleMemory {
    var enabled = false
    private var loaded = false
    /** Restores the last choice once, when the setting asks for it. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val settings = fr.spotifeur.data.preferences.SettingsStore.get(context).settings.value
        enabled = settings.persistentShuffle && context.getSharedPreferences("player_state", 0).getBoolean("shuffle", false)
    }
    fun save(context: Context) = context.getSharedPreferences("player_state", 0).edit().putBoolean("shuffle", enabled).apply()
    /** Queue order to restore when shuffle is turned off. */
    var original: List<Music>? = null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpotifyPlayer(context: Context, scope: CoroutineScope) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(Playback())
    val state = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var pending: Pair<List<Music>, Int>? = null
    private val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, SpotifeurPlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueChanged = true
            update(player)
        }
    }
    /**
     * The queue as [Music], rebuilt only when the timeline changes. A long queue (a playlist of
     * a few thousand tracks) would otherwise be converted and compared on the main thread at every
     * position tick and player event, delaying taps and track changes.
     */
    private var queue: List<Music> = emptyList()
    private var queueChanged = true
    /**
     * The player holds a window of the context around the current track, as Spotify's own player
     * does: a few thousand media items would make every timeline change slow to copy between the
     * controller and the session. The rest of the context waits here and is added as playback moves.
     */
    private var before: List<Music> = emptyList()
    private var after: List<Music> = emptyList()
    init {
        ShuffleMemory.load(appContext)
        future.addListener({
            try {
                controller = future.get().also { it.addListener(listener) }
                pending?.let { (queue, index) -> pending = null; load(queue, index) } ?: controller?.let(::update)
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(buffering = false, error = "The player could not start.") }
        }, ContextCompat.getMainExecutor(context))
        scope.launch { while (true) { delay(300); controller?.let(::update) } }
    }
    fun play(queue: List<Music>, index: Int = 0) {
        val selected = queue.getOrNull(index) ?: return
        if (!selected.isPlayableTrack()) return
        val playable = queue.filter(Music::isPlayableTrack)
        val first = queue.take(index).count(Music::isPlayableTrack)
        // Shuffle reorders the queue itself: the chosen track plays first and every other one follows.
        ShuffleMemory.original = playable.takeIf { ShuffleMemory.enabled }
        val tracks = if (ShuffleMemory.enabled) listOf(playable[first]) + (playable.take(first) + playable.drop(first + 1)).shuffled() else playable
        val start = if (ShuffleMemory.enabled) 0 else first
        mutableState.value = Playback(window(tracks, start).first, start - windowStart(start), buffering = true,
            durationMs = selected.durationMs, shuffle = ShuffleMemory.enabled, canNext = start < tracks.lastIndex)
        if (controller == null) { pending = tracks to start; return }
        load(tracks, start)
    }
    private fun windowStart(start: Int) = (start - WINDOW_BEHIND).coerceAtLeast(0)
    /** The part of [tracks] the player holds when [start] plays, and what stays out before and after it. */
    private fun window(tracks: List<Music>, start: Int): Triple<List<Music>, List<Music>, List<Music>> {
        val from = windowStart(start)
        val to = (start + WINDOW_AHEAD + 1).coerceAtMost(tracks.size)
        return Triple(tracks.subList(from, to).toList(), tracks.subList(0, from).toList(), tracks.subList(to, tracks.size).toList())
    }
    private fun load(tracks: List<Music>, start: Int) {
        val player = controller ?: return
        val (shown, earlier, later) = window(tracks, start)
        before = earlier; after = later
        queueChanged = true
        // As in Spotify, the tracks the user queued survive a new context and play right after its first track.
        val queued = userQueue(player)
        val first = start - windowStart(start)
        val items = shown.map(::mediaItem).toMutableList().apply { addAll(first + 1, queued.map { mediaItem(it, queued = true) }) }
        player.setMediaItems(items, first, 0L); player.prepare(); player.play()
    }
    /** The tracks the user queued, waiting right after the current one. */
    private fun userQueue(player: Player): List<Music> {
        val count = player.mediaItemCount
        if (count == 0) return emptyList()
        return (player.currentMediaItemIndex + 1 until count).asSequence()
            .map(player::getMediaItemAt).takeWhile(MediaItem::isQueued).map(::music).toList()
    }
    /**
     * Spotify's "Add to queue": after the current track and after what the user already queued,
     * before the rest of the playlist or album. Queued tracks are never shuffled.
     */
    fun addToQueue(tracks: List<Music>) { controller?.let { player ->
        val valid = tracks.filter(Music::isPlayableTrack)
        if (valid.isEmpty()) return
        if (player.mediaItemCount == 0) { play(valid, 0); return }
        val position = player.currentMediaItemIndex + 1 + userQueue(player).size
        player.addMediaItems(position, valid.map { mediaItem(it, queued = true) })
        queueChanged = true; update(player)
    } }
    /** "Play next": at the head of the user's queue. */
    fun playNext(tracks: List<Music>) { controller?.let { player ->
        val valid = tracks.filter(Music::isPlayableTrack)
        if (valid.isEmpty()) return
        if (player.mediaItemCount == 0) { play(valid, 0); return }
        player.addMediaItems(player.currentMediaItemIndex + 1, valid.map { mediaItem(it, queued = true) })
        queueChanged = true; update(player)
    } }
    /** The whole context and the position of the current track in it. */
    fun context(): Pair<List<Music>, Int> = (before + queue) + after to before.size + mutableState.value.index
    /** Keeps a margin of tracks on both sides of the current one, and the player small. */
    private fun refill(player: Player) {
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex
        if (count - 1 - current < REFILL_AT) {
            // With "repeat all", the start of the context follows its end.
            val source = if (after.isEmpty() && player.repeatMode == Player.REPEAT_MODE_ALL) before else after
            if (source.isNotEmpty()) {
                val chunk = source.take(CHUNK)
                if (source === after) after = after.drop(CHUNK) else before = before.drop(CHUNK)
                player.addMediaItems(chunk.map(::mediaItem)); queueChanged = true
            }
        }
        if (current < REFILL_AT && before.isNotEmpty()) {
            val chunk = before.takeLast(CHUNK)
            before = before.dropLast(CHUNK)
            player.addMediaItems(0, chunk.map(::mediaItem)); queueChanged = true
        } else if (current > WINDOW_BEHIND + CHUNK) {
            val dropped = (0 until current - WINDOW_BEHIND).map { music(player.getMediaItemAt(it)) }
            before = before + dropped
            player.removeMediaItems(0, dropped.size); queueChanged = true
        }
    }
    fun toggle() {
        val player = controller ?: return
        togglePlayback(player)
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun previousTrack() {
        val player = controller ?: return
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
    }
    fun seek(positionMs: Int) { controller?.let { player ->
        val failed = player.playerError != null
        player.seekTo(positionMs.coerceAtLeast(0).toLong())
        if (failed) player.prepare()
    } }
    fun shuffle() { controller?.let { player ->
        val queued = userQueue(player)
        val window = (0 until player.mediaItemCount).map { music(player.getMediaItemAt(it)) }
        val local = player.currentMediaItemIndex
        // The user's queue stays apart: never shuffled, always first after the current track.
        val items = before + window.take(local + 1) + window.drop(local + 1 + queued.size) + after
        val current = before.size + local
        ShuffleMemory.enabled = !ShuffleMemory.enabled
        ShuffleMemory.save(appContext)
        val original = ShuffleMemory.original
        when {
            items.isEmpty() -> ShuffleMemory.original = null
            ShuffleMemory.enabled -> {
                ShuffleMemory.original = items
                replaceAroundCurrent(player, emptyList(), (items.take(current) + items.drop(current + 1)).shuffled(), queued)
            }
            original != null -> {
                ShuffleMemory.original = null
                val position = original.indexOfFirst { it.id == items.getOrNull(current)?.id }
                if (position >= 0) replaceAroundCurrent(player, original.take(position), original.drop(position + 1), queued)
            }
        }
        queueChanged = true
        update(player)
    } }
    /** The current item is never removed, so its audio keeps playing while the queue changes around it. */
    private fun replaceAroundCurrent(player: Player, earlier: List<Music>, later: List<Music>, queued: List<Music> = emptyList()) {
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        val shownAfter = later.take(WINDOW_AHEAD)
        val shownBefore = earlier.takeLast(WINDOW_BEHIND)
        before = earlier.dropLast(WINDOW_BEHIND); after = later.drop(WINDOW_AHEAD)
        val following = queued.map { mediaItem(it, queued = true) } + shownAfter.map(::mediaItem)
        if (following.isNotEmpty()) player.addMediaItems(1, following)
        if (shownBefore.isNotEmpty()) player.addMediaItems(0, shownBefore.map(::mediaItem))
    }
    fun repeat() { controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }
    fun stop() {
        pending = null; ShuffleMemory.original = null
        queueChanged = true
        before = emptyList(); after = emptyList()
        controller?.stop(); controller?.clearMediaItems(); mutableState.value = Playback(shuffle = ShuffleMemory.enabled)
    }
    fun pause() { controller?.pause() }
    fun setVolume(volume: Float) { controller?.volume = volume }
    fun volume(): Float = controller?.volume ?: 1f
    fun seekToItem(index: Int) { controller?.let { player ->
        val failed = player.playerError != null
        player.seekToDefaultPosition(index)
        if (failed) player.prepare()
        player.play()
    } }
    fun insert(index: Int, tracks: List<Music>) {
        val valid = tracks.filter(Music::isPlayableTrack)
        if (valid.isEmpty()) return
        controller?.let {
            it.addMediaItems(index.coerceIn(0, it.mediaItemCount), valid.map(::mediaItem))
            queueChanged = true; update(it)
        }
    }
    fun removeAt(index: Int) { controller?.let { player ->
        val id = player.getMediaItemAt(index).mediaId
        ShuffleMemory.original = ShuffleMemory.original?.let { list ->
            list.indexOfFirst { it.id == id }.let { found -> if (found < 0) list else list.take(found) + list.drop(found + 1) }
        }
        player.removeMediaItem(index); queueChanged = true; update(player)
    } }
    fun move(from: Int, to: Int) { controller?.let { it.moveMediaItem(from, to); queueChanged = true; update(it) } }
    fun clearExceptCurrent() { controller?.let { player ->
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        ShuffleMemory.original = null
        before = emptyList(); after = emptyList()
        queueChanged = true
        update(player)
    } }
    private fun update(player: Player) {
        refill(player)
        if (queueChanged || queue.size != player.mediaItemCount) {
            queue = (0 until player.mediaItemCount).map { music(player.getMediaItemAt(it)) }
            queueChanged = false
        }
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val duration = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 }
            ?: queue.getOrNull(index)?.durationMs?.toLong() ?: 0
        val error = player.playerError?.let { failure ->
            generateSequence<Throwable>(failure) { it.cause }.take(12)
                .firstOrNull { it is SpotifyFailure || it is fr.spotifeur.data.youtube.YouTubeFailure }?.message
                ?: if (!fr.spotifeur.data.NetworkMonitor.online.value) "This track is not available offline."
                else playbackErrorMessage(failure.errorCode)
        }
        mutableState.value = Playback(queue, index, player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
            player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ShuffleMemory.enabled, player.repeatMode, error, player.hasNextMediaItem() || after.isNotEmpty(), player.playWhenReady, currentAudioFormat(player),
            userQueue(player).size)
    }
    private companion object {
        const val WINDOW_BEHIND = 20
        const val WINDOW_AHEAD = 100
        const val REFILL_AT = 10
        const val CHUNK = 50
    }
    override fun close() { controller?.removeListener(listener); MediaController.releaseFuture(future); controller = null }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun currentAudioFormat(player: Player): String {
    val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return ""
    val format = (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) ?: return ""
    return fr.spotifeur.data.audioQualityLabel(format.sampleMimeType, format.sampleRate.takeIf { it > 0 }, format.bitrate.takeIf { it > 0 })
}
internal fun togglePlayback(player: Player) {
    if (player.playerError != null) { player.prepare(); player.play() }
    else if (player.playWhenReady) player.pause() else {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }
}
internal fun playbackErrorMessage(code: Int): String = when (code) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT -> "The audio connection was interrupted. Check the network, then try again."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The audio stream could not be provided. Try again in a moment."
    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
    PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> "Playback authorization could not be obtained. Try again."
    PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> "Spotify does not allow this track to play in this session."
    PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
    PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
    PlaybackException.ERROR_CODE_DECODING_FAILED -> "The received audio stream cannot be decoded. Try again or pick another track."
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "Audio output could not start. Check the audio device, then try again."
    else -> "This track cannot be played. Try again or pick another track."
}
private fun Music.isPlayableTrack(): Boolean = kind == MusicKind.Track &&
    (id.startsWith("spotify:track:") || id.startsWith(fr.spotifeur.data.youtube.YTM_TRACK_PREFIX) || (isLocalTrack && mediaUri != null))

/** Set on the tracks the user queued ("Add to queue", "Play next"). */
private const val QUEUED = "queued"
private fun MediaItem.isQueued(): Boolean = mediaMetadata.extras?.getBoolean(QUEUED) == true

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun mediaItem(track: Music, queued: Boolean = false): MediaItem {
    // YouTube Music tracks, and Spotify tracks without a Spotify account, are read from YouTube Music.
    val youtube = YouTubeUris.of(track, fr.spotifeur.data.SpotifyAccount.signedIn)
    val spotify = youtube == null && !track.isLocalTrack
    return MediaItem.Builder().setMediaId(track.id)
        .setUri(youtube ?: track.mediaUri?.let(Uri::parse) ?: Uri.parse(track.id))
        .apply { if (youtube != null) setCustomCacheKey(YouTubeUris.cacheKey(track.id)) }
        .apply { if (spotify) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .apply { if (!BuildConfig.NATIVE_RUNTIME && spotify) setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri("https://spclient.wg.spotify.com/widevine-license/v1/audio/license")
            .setMultiSession(true).setPlayClearContentWithoutKey(true).build()) }
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.subtitle)
            .setArtworkUri(fr.spotifeur.data.largestSpotifyImage(track.imageUrl)?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false)
            .setExtras(Bundle().apply {
                putInt("duration", track.durationMs); putInt("artwork", track.artwork)
                putString("album", track.album); putString("mediaUri", track.mediaUri); putString("quality", track.quality)
                if (queued) putBoolean(QUEUED, true)
            }).build()).build()
}
private fun music(item: MediaItem) = musicOf(item)
