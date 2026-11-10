package fr.spotifeur.data.preferences

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Every user-facing preference of the app. Never holds account material. */
data class AppSettings(
    val themeMode: String = THEME_SYSTEM,
    val language: String = LANGUAGE_SYSTEM,
    val useSmoothCorners: Boolean = true,
    val disableBlur: Boolean = false,
    val showScrollbar: Boolean = true,
    val playerTheme: String = ThemePreference.ALBUM_ART,
    val palette: String = AlbumArtPaletteStyle.default.storageKey,
    val carouselStyle: String = CarouselStyle.NO_PEEK,
    val showPlayerFileInfo: Boolean = true,
    val showQualityInLists: Boolean = false,
    val collagePattern: String = CollagePattern.default.storageKey,
    val collageAutoRotate: Boolean = false,
    val navBarStyle: String = NavBarStyle.DEFAULT,
    val navBarCompact: Boolean = false,
    val navBarCornerRadius: Int = 32,
    val immersiveLyrics: Boolean = false,
    val immersiveLyricsTimeoutMs: Long = 4000L,
    val lyricsFallback: Boolean = true,
    val defaultTab: String = TAB_HOME,
    val keepPlayingInBackground: Boolean = true,
    val pauseOnVolumeZero: Boolean = false,
    val resumeOnHeadphones: Boolean = false,
    val persistentShuffle: Boolean = false,
    val smartShuffle: Boolean = false,
    val showQueueHistory: Boolean = true,
    val audioCacheLimitMb: Int = 4096,
    val preloadTracks: Int = 10,
    val preloadEntireQueue: Boolean = false,
    val preloadOnMobile: Boolean = true,
    val offlineMode: Boolean = false,
    val tapBackgroundClosesPlayer: Boolean = false,
    val hapticsEnabled: Boolean = true,
    /** Source badge on each track of a multi-service playlist. */
    val showSourceBadges: Boolean = true,
    /** Smaller rows and spacing in track and library lists. */
    val compactLists: Boolean = false,
    /** Home shelves: order, visibility, layout and size, as [HomeLayout] encodes them. Empty: defaults. */
    val homeShelves: String = "",
    /** Album and playlist page buttons: order and which ones are in the bar, as [DetailActions] encodes them. */
    val albumActions: String = "",
    val playlistActions: String = "",
    /** What swiping a track row does, as [fr.spotifeur.presentation.components.TrackSwipeAction] keys. */
    val swipeRight: String = "queue",
    val swipeLeft: String = "none",
    /** [fr.spotifeur.data.AudioQuality] keys for streaming on Wi-Fi, on mobile data, and for downloads. */
    val streamingQualityWifi: String = "auto",
    val streamingQualityMobile: String = "auto",
    val downloadQuality: String = "very_high",
) {
    fun actions(page: DetailPage): List<ActionSlot> =
        DetailActions.decode(page, if (page == DetailPage.ALBUM) albumActions else playlistActions)
    val homeLayout: List<ShelfConfig> get() = HomeLayout.decode(homeShelves)
    fun toJson(): JSONObject = JSONObject()
        .put("themeMode", themeMode).put("language", language).put("useSmoothCorners", useSmoothCorners)
        .put("disableBlur", disableBlur).put("showScrollbar", showScrollbar).put("playerTheme", playerTheme)
        .put("palette", palette).put("carouselStyle", carouselStyle).put("showPlayerFileInfo", showPlayerFileInfo)
        .put("showQualityInLists", showQualityInLists).put("collagePattern", collagePattern)
        .put("collageAutoRotate", collageAutoRotate).put("navBarStyle", navBarStyle).put("navBarCompact", navBarCompact)
        .put("navBarCornerRadius", navBarCornerRadius).put("immersiveLyrics", immersiveLyrics)
        .put("immersiveLyricsTimeoutMs", immersiveLyricsTimeoutMs).put("lyricsFallback", lyricsFallback)
        .put("defaultTab", defaultTab).put("keepPlayingInBackground", keepPlayingInBackground)
        .put("pauseOnVolumeZero", pauseOnVolumeZero).put("resumeOnHeadphones", resumeOnHeadphones)
        .put("persistentShuffle", persistentShuffle).put("smartShuffle", smartShuffle).put("showQueueHistory", showQueueHistory)
        .put("audioCacheLimitMb", audioCacheLimitMb).put("preloadTracks", preloadTracks)
        .put("preloadEntireQueue", preloadEntireQueue).put("preloadOnMobile", preloadOnMobile).put("offlineMode", offlineMode)
        .put("tapBackgroundClosesPlayer", tapBackgroundClosesPlayer).put("hapticsEnabled", hapticsEnabled)
        .put("showSourceBadges", showSourceBadges).put("compactLists", compactLists).put("homeShelves", homeShelves)
        .put("albumActions", albumActions).put("playlistActions", playlistActions)
        .put("swipeRight", swipeRight).put("swipeLeft", swipeLeft)
        .put("streamingQualityWifi", streamingQualityWifi).put("streamingQualityMobile", streamingQualityMobile)
        .put("downloadQuality", downloadQuality)

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val LANGUAGE_SYSTEM = "system"
        const val TAB_HOME = "home"
        const val TAB_SEARCH = "search"
        const val TAB_LIBRARY = "library"

        /** Unknown or missing fields keep their default, so old backups stay valid. */
        fun fromJson(json: JSONObject): AppSettings {
            val d = AppSettings()
            return AppSettings(
                themeMode = json.optString("themeMode", d.themeMode),
                language = json.optString("language", d.language),
                useSmoothCorners = json.optBoolean("useSmoothCorners", d.useSmoothCorners),
                disableBlur = json.optBoolean("disableBlur", d.disableBlur),
                showScrollbar = json.optBoolean("showScrollbar", d.showScrollbar),
                playerTheme = json.optString("playerTheme", d.playerTheme),
                palette = json.optString("palette", d.palette),
                carouselStyle = json.optString("carouselStyle", d.carouselStyle),
                showPlayerFileInfo = json.optBoolean("showPlayerFileInfo", d.showPlayerFileInfo),
                showQualityInLists = json.optBoolean("showQualityInLists", d.showQualityInLists),
                collagePattern = json.optString("collagePattern", d.collagePattern),
                collageAutoRotate = json.optBoolean("collageAutoRotate", d.collageAutoRotate),
                navBarStyle = json.optString("navBarStyle", d.navBarStyle),
                navBarCompact = json.optBoolean("navBarCompact", d.navBarCompact),
                navBarCornerRadius = sanitizeNavBarCornerRadius(json.optInt("navBarCornerRadius", d.navBarCornerRadius)),
                immersiveLyrics = json.optBoolean("immersiveLyrics", d.immersiveLyrics),
                immersiveLyricsTimeoutMs = json.optLong("immersiveLyricsTimeoutMs", d.immersiveLyricsTimeoutMs),
                lyricsFallback = json.optBoolean("lyricsFallback", d.lyricsFallback),
                defaultTab = json.optString("defaultTab", d.defaultTab),
                keepPlayingInBackground = json.optBoolean("keepPlayingInBackground", d.keepPlayingInBackground),
                pauseOnVolumeZero = json.optBoolean("pauseOnVolumeZero", d.pauseOnVolumeZero),
                resumeOnHeadphones = json.optBoolean("resumeOnHeadphones", d.resumeOnHeadphones),
                persistentShuffle = json.optBoolean("persistentShuffle", d.persistentShuffle),
                smartShuffle = json.optBoolean("smartShuffle", d.smartShuffle),
                showQueueHistory = json.optBoolean("showQueueHistory", d.showQueueHistory),
                audioCacheLimitMb = json.optInt("audioCacheLimitMb", d.audioCacheLimitMb).coerceIn(512, 32768),
                preloadTracks = (if (json.optBoolean("preloadEntireQueue", false)) 10 else json.optInt("preloadTracks", d.preloadTracks)).coerceIn(0, 20),
                preloadEntireQueue = false,
                preloadOnMobile = json.optBoolean("preloadOnMobile", d.preloadOnMobile),
                offlineMode = json.optBoolean("offlineMode", d.offlineMode),
                tapBackgroundClosesPlayer = json.optBoolean("tapBackgroundClosesPlayer", d.tapBackgroundClosesPlayer),
                hapticsEnabled = json.optBoolean("hapticsEnabled", d.hapticsEnabled),
                showSourceBadges = json.optBoolean("showSourceBadges", d.showSourceBadges),
                compactLists = json.optBoolean("compactLists", d.compactLists),
                homeShelves = json.optString("homeShelves", d.homeShelves),
                albumActions = json.optString("albumActions", d.albumActions),
                playlistActions = json.optString("playlistActions", d.playlistActions),
                swipeRight = json.optString("swipeRight", d.swipeRight),
                swipeLeft = json.optString("swipeLeft", d.swipeLeft),
                streamingQualityWifi = json.optString("streamingQualityWifi", d.streamingQualityWifi),
                streamingQualityMobile = json.optString("streamingQualityMobile", d.streamingQualityMobile),
                downloadQuality = json.optString("downloadQuality", d.downloadQuality),
            )
        }
    }
}

class SettingsStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = mutable.asStateFlow()

    private fun read(): AppSettings = preferences.getString(KEY, null)?.let { saved ->
        try { AppSettings.fromJson(JSONObject(saved)) } catch (_: org.json.JSONException) { null }
    } ?: AppSettings()

    fun update(change: (AppSettings) -> AppSettings) {
        mutable.update(change)
        preferences.edit { putString(KEY, mutable.value.toJson().toString()) }
    }

    companion object {
        private const val KEY = "settings"
        @Volatile private var instance: SettingsStore? = null
        /** Shared with the playback service, which runs in the same process. */
        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context).also { instance = it }
        }
    }
}
