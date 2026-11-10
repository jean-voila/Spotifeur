package fr.spotifeur.playback

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.cancel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import fr.spotifeur.BuildConfig
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import fr.spotifeur.MainActivity
import fr.spotifeur.data.preferences.SettingsStore
import fr.spotifeur.data.SpotifyHlsDataSource

@UnstableApi
class SpotifeurPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var source: SpotifyHlsDataSource.Factory? = null
    private var nativeAudio: NativeAudioProvider? = null
    private var behavior: PlaybackBehavior? = null
    private var preloader: QueuePreloader? = null
    private var recovery: PlaybackRecovery? = null
    private var normalizingSink: NormalizingAudioSink? = null
    private val effectsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob())
    override fun onCreate() {
        super.onCreate()
        val audio = if (BuildConfig.NATIVE_RUNTIME) NativeAudioProvider(this).also { nativeAudio = it; currentNativeAudio = it } else null
        val http = SpotifyHlsDataSource.Factory(this, nativeAudio = audio != null).also { source = it }
        // Betamax's audio provider (h9v.b in the supplied APK) explicitly selects L3.
        // Configure the platform CDM before any session is opened; it retains all keys.
        val drm = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid ->
                FrameworkMediaDrm.newInstance(uuid).apply { setPropertyString("securityLevel", "L3") }
            }
            .setMultiSession(true).setPlayClearSamplesWithoutKeys(true)
            .build(HttpMediaDrmCallback("https://spclient.wg.spotify.com/widevine-license/v1/audio/license", false, http))
        val renderers = object : DefaultRenderersFactory(this) {
            override fun getCodecAdapterFactory(): androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory {
                val factory = super.getCodecAdapterFactory()
                if (audio != null) return audio.codecFactory
                if (!BuildConfig.DEBUG) return factory
                return androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory { configuration ->
                    val codec = factory.createAdapter(configuration)
                    var count = 0
                    object : androidx.media3.exoplayer.mediacodec.MediaCodecAdapter by codec {
                        override fun queueSecureInputBuffer(index: Int, offset: Int, info: androidx.media3.decoder.CryptoInfo, timeUs: Long, flags: Int) {
                            if (++count % 200 == 1) android.util.Log.i("SpotifeurAudio", "secure mode=${info.mode} ivSize=${info.iv?.size} ivPresent=${info.iv?.any { it.toInt() != 0 }} sub=${info.numSubSamples} clear=${info.numBytesOfClearData?.sum()} encrypted=${info.numBytesOfEncryptedData?.sum()} blocks=${info.encryptedBlocks}/${info.clearBlocks} crypto=${configuration.crypto != null}")
                            codec.queueSecureInputBuffer(index, offset, info, timeUs, flags)
                        }
                    }
                }
            }
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                NormalizingAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .apply { if (BuildConfig.BUILD_TYPE == "debug") setAudioProcessors(arrayOf(SignalProbe())) }.build())
                    .also { normalizingSink = it }
        }
        // Playlists have no length and stay out of both caches: the data source keeps those itself.
        val cacheFlags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS
        val recentAudio = CacheDataSource.Factory().setCache(AudioCache.cache(this)).setCacheKeyFactory(AudioCache.keys)
            .setUpstreamDataSourceFactory(http).setFlags(cacheFlags)
        // YouTube Music requests have no known length: they use their own chain, keyed by track.
        val youtube = youTubeCacheChain(this)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(SpotifeurMediaSourceFactory(HlsMediaSource.Factory(
                CacheDataSource.Factory().setCache(DownloadCenter.cache(this)).setCacheKeyFactory(AudioCache.keys)
                    .setUpstreamDataSourceFactory(recentAudio)
                    .setCacheWriteDataSinkFactory(null)
                    .setFlags(cacheFlags)
            ).setLoadErrorHandlingPolicy(ResilientLoadPolicy()).apply {
                if (audio != null) setExtractorFactory(NativeHlsExtractor(audio))
                else { setDrmSessionManagerProvider { drm }; if (BuildConfig.DEBUG) setExtractorFactory(ExtractorProbe()) }
            }, ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this)),
                ProgressiveMediaSource.Factory(youtube.kept).setLoadErrorHandlingPolicy(ResilientLoadPolicy())))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(NEXT_TRACK_PRELOAD_US)
        player.addAnalyticsListener(PlaybackDiagnostics)
        preloader = QueuePreloader(this, player, http, recentAudio, effectsScope, audio, youtube.recent).also { it.start() }
        recovery = PlaybackRecovery(this, player, effectsScope, audio).also { it.start() }
        behavior = PlaybackBehavior(this, player).also { it.start() }
        EqualizerRuntime.start(this, effectsScope)
        EqualizerRuntime.setAudioSessionId(player.audioSessionId)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = EqualizerRuntime.setAudioSessionId(audioSessionId)
            // The first track's loudness is only known once its manifest is read, hence READY too.
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updateNormalization(player)
            override fun onPlaybackStateChanged(playbackState: Int) = updateNormalization(player)
        })
        effectsScope.launch {
            SettingsStore.get(this@SpotifeurPlaybackService).settings.map { it.audioNormalization }.distinctUntilChanged()
                .collect { updateNormalization(player) }
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(intent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    if (controller.packageName == packageName || controller.isTrusted) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.reject()
            }).build()
    }
    private fun updateNormalization(player: androidx.media3.common.Player) {
        val sink = normalizingSink ?: return
        val enabled = SettingsStore.get(this).settings.value.audioNormalization
        val id = player.currentMediaItem?.mediaId
        val gain = if (!enabled || id == null) null else TrackLoudness.gainDb(this, id)
        val factor = if (gain == null) 1f else TrackLoudness.linear(gain)
        if (factor != sink.factor) android.util.Log.i("SpotifeurAudio", "normalization enabled=$enabled gain=${gain?.let { "%.1f".format(it) }}dB volume=${"%.2f".format(factor)}")
        sink.factor = factor
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onTaskRemoved(rootIntent: Intent?) {
        val keepPlaying = SettingsStore.get(this).settings.value.keepPlayingInBackground
        if (!keepPlaying) mediaSession?.player?.pause()
        if (!keepPlaying || mediaSession?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        behavior?.close(); behavior = null
        preloader?.close(); preloader = null
        recovery?.close(); recovery = null
        effectsScope.cancel()
        EqualizerRuntime.setAudioSessionId(0)
        nativeAudio?.cancelPending()
        mediaSession?.run { player.release(); release() }; mediaSession = null
        nativeAudio?.close(); nativeAudio = null
        source?.close(); source = null
        super.onDestroy()
    }

    internal class YouTubeChain(val recent: CacheDataSource.Factory, val kept: CacheDataSource.Factory)

    internal companion object {
        /** Downloads first, then the recent cache, then the network. Nothing is written to downloads while listening. */
        fun youTubeCacheChain(context: android.content.Context): YouTubeChain {
            val recent = CacheDataSource.Factory().setCache(AudioCache.cache(context)).setCacheKeyFactory(AudioCache.keys)
                .setUpstreamDataSourceFactory(YouTubeDataSource.Factory(context)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val kept = CacheDataSource.Factory().setCache(DownloadCenter.cache(context)).setCacheKeyFactory(AudioCache.keys)
                .setUpstreamDataSourceFactory(recent).setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            return YouTubeChain(recent, kept)
        }
        private const val NEXT_TRACK_PRELOAD_US = 30_000_000L
        /** Instrumented tests only. */
        @Volatile internal var currentNativeAudio: NativeAudioProvider? = null
    }
}
