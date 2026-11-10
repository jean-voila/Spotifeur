package fr.spotifeur.presentation.screens

import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import fr.spotifeur.presentation.components.TrackSwipeAction

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.imageLoader
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.preferences.AlbumArtPaletteStyle
import fr.spotifeur.data.preferences.AppSettings
import fr.spotifeur.data.preferences.CarouselStyle
import fr.spotifeur.data.preferences.CollagePattern
import fr.spotifeur.data.preferences.NavBarStyle
import fr.spotifeur.data.preferences.ThemePreference
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.ui.theme.GoogleSansRounded
import fr.spotifeur.utils.AppLocales
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsScaffold(title: String, navController: NavHostController, content: @Composable ColumnScope.() -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, fontFamily = GoogleSansRounded, modifier = Modifier.padding(start = 8.dp)) },
                navigationIcon = {
                    FilledTonalIconButton(
                        modifier = Modifier.padding(start = 10.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = { navController.popBackStack() }
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                // Room for the mini player and the navigation bar under the last item.
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = 32.dp + fr.spotifeur.presentation.components.MiniPlayerHeight +
                        androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
private fun CategoryRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
    }
}

@Composable
fun SettingsScreen(model: SpotifeurModel, navController: NavHostController) {
    val settings by model.ui.settings.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.common_settings), navController) {
        SwitchSettingItem(stringResource(R.string.offline_mode_title), stringResource(R.string.offline_mode_description), settings.offlineMode,
            { enabled -> model.ui.updateSettings { it.copy(offlineMode = enabled) } },
            leadingIcon = { Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.secondary) })
        fun open(id: String) = navController.navigateSafely(Screen.SettingsCategory.createRoute(id))
        CategoryRow(stringResource(R.string.settings_category_appearance_title), stringResource(R.string.settings_category_appearance_subtitle), Icons.Rounded.Palette) { open("appearance") }
        CategoryRow(stringResource(R.string.settings_home_title), stringResource(R.string.settings_home_subtitle), Icons.Rounded.Home) { open("home") }
        CategoryRow(stringResource(R.string.settings_pages_title), stringResource(R.string.settings_pages_subtitle), Icons.Rounded.Tune) { open("pages") }
        CategoryRow(stringResource(R.string.settings_category_playback_title), stringResource(R.string.settings_category_playback_subtitle), Icons.Rounded.MusicNote) { open("playback") }
        CategoryRow(stringResource(R.string.settings_category_behavior_title), stringResource(R.string.settings_category_behavior_subtitle), Icons.Rounded.TouchApp) { open("behavior") }
        CategoryRow(stringResource(R.string.settings_category_equalizer_title), stringResource(R.string.settings_category_equalizer_subtitle), Icons.Rounded.GraphicEq) { navController.navigateSafely(Screen.Equalizer.route) }
        CategoryRow(stringResource(R.string.settings_storage_title), stringResource(R.string.settings_storage_subtitle), Icons.Rounded.Storage) { open("storage") }
        CategoryRow(stringResource(R.string.settings_category_backup_title), stringResource(R.string.settings_category_backup_subtitle), Icons.Rounded.Backup) { open("backup") }
        val discovery by model.discovery.collectAsStateWithLifecycle()
        val google by model.youTubeAccount.collectAsStateWithLifecycle()
        CategoryRow(stringResource(R.string.settings_accounts_title),
            listOfNotNull(stringResource(R.string.search_source_spotify).takeIf { discovery.spotifyEnabled },
                stringResource(R.string.search_source_youtube).takeIf { google != null })
                .joinToString(" · ").ifEmpty { stringResource(R.string.settings_account_guest_subtitle) },
            Icons.AutoMirrored.Rounded.Logout) { open("account") }
        val context = LocalContext.current
        CategoryRow(stringResource(R.string.update_settings_title),
            stringResource(R.string.update_installed, fr.spotifeur.BuildConfig.VERSION_NAME),
            Icons.Rounded.Download) { fr.spotifeur.update.AppUpdater.get(context).check(manual = true) }
        CategoryRow(stringResource(R.string.diagnostics_title), stringResource(R.string.diagnostics_subtitle),
            Icons.Rounded.BugReport) { open("diagnostics") }
    }
}

@Composable
fun SettingsCategoryScreen(model: SpotifeurModel, navController: NavHostController, category: String) {
    when (category) {
        "appearance" -> AppearanceSettings(model, navController)
        "playback" -> PlaybackSettings(model, navController)
        "behavior" -> BehaviorSettings(model, navController)
        "storage" -> StorageSettings(model, navController)
        "audio_cache" -> AudioCacheScreen(model, navController)
        "backup" -> BackupSettings(model, navController)
        "account" -> AccountSettings(model, navController)
        "home" -> HomeSettings(model, navController)
        "pages" -> PageActionsSettings(model, navController)
        "diagnostics" -> DiagnosticsSettings(model, navController)
    }
}

@Composable
private fun AppearanceSettings(model: SpotifeurModel, navController: NavHostController) {
    val ui = model.ui
    val s by ui.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val update = ui::updateSettings
    SettingsScaffold(stringResource(R.string.settings_category_appearance_title), navController) {
        SettingsSection(title = stringResource(R.string.settings_global_theme_section), icon = {}) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_app_language_title), description = "",
                options = mapOf(
                    AppSettings.LANGUAGE_SYSTEM to stringResource(R.string.settings_language_system),
                    "fr" to "Français", "en" to "English",
                ),
                selectedKey = s.language,
                onSelectionChanged = { key -> update { it.copy(language = key) }; AppLocales.apply(context, key) },
                leadingIcon = { Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_app_theme_title), description = "",
                options = mapOf(
                    AppSettings.THEME_SYSTEM to stringResource(R.string.settings_language_system),
                    AppSettings.THEME_LIGHT to stringResource(R.string.settings_theme_light),
                    AppSettings.THEME_DARK to stringResource(R.string.settings_theme_dark),
                ),
                selectedKey = s.themeMode,
                onSelectionChanged = { key -> update { it.copy(themeMode = key) } },
                leadingIcon = { Icon(Icons.Outlined.LightMode, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_smooth_corners_title), stringResource(R.string.settings_smooth_corners_subtitle), s.useSmoothCorners, { v -> update { it.copy(useSmoothCorners = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_rounded_corner_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_disable_blur_all_over_title), stringResource(R.string.settings_disable_blur_all_over_subtitle), s.disableBlur, { v -> update { it.copy(disableBlur = v) } }, leadingIcon = { Icon(Icons.Rounded.BlurOff, null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_show_scrollbar_title), stringResource(R.string.settings_show_scrollbar_subtitle), s.showScrollbar, { v -> update { it.copy(showScrollbar = v) } }, leadingIcon = { Icon(Icons.Rounded.UnfoldMore, null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_lists_section), icon = {}) {
            SwitchSettingItem(stringResource(R.string.settings_compact_lists_title), stringResource(R.string.settings_compact_lists_subtitle), s.compactLists, { v -> update { it.copy(compactLists = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_view_week_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_source_badges_title), stringResource(R.string.settings_source_badges_subtitle), s.showSourceBadges, { v -> update { it.copy(showSourceBadges = v) } }, leadingIcon = { Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_now_playing_section), icon = {}) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_player_theme_title), description = "",
                options = mapOf(
                    ThemePreference.ALBUM_ART to stringResource(R.string.settings_player_theme_album),
                    ThemePreference.GLOBAL to stringResource(R.string.settings_player_theme_app),
                ),
                selectedKey = s.playerTheme,
                onSelectionChanged = { key -> update { it.copy(playerTheme = key) } },
                leadingIcon = { Icon(Icons.Outlined.PlayCircle, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_album_art_palette_title), description = "",
                options = AlbumArtPaletteStyle.entries.associate { it.storageKey to it.label },
                selectedKey = s.palette,
                onSelectionChanged = { key -> update { it.copy(palette = key) } },
                leadingIcon = { Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_carousel_style_title), description = "",
                options = mapOf(
                    CarouselStyle.NO_PEEK to stringResource(R.string.settings_carousel_none),
                    CarouselStyle.ONE_PEEK to stringResource(R.string.settings_carousel_one),
                    CarouselStyle.TWO_PEEK to stringResource(R.string.settings_carousel_two),
                ),
                selectedKey = s.carouselStyle,
                onSelectionChanged = { key -> update { it.copy(carouselStyle = key) } },
                leadingIcon = { Icon(painterResource(R.drawable.rounded_view_carousel_24), null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_show_player_file_info_title), stringResource(R.string.settings_show_player_file_info_subtitle), s.showPlayerFileInfo, { v -> update { it.copy(showPlayerFileInfo = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_attach_file_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_quality_lists_title), stringResource(R.string.settings_quality_lists_subtitle), s.showQualityInLists, { v -> update { it.copy(showQualityInLists = v) } }, leadingIcon = { Icon(painterResource(R.drawable.outline_high_quality_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_player_menu_title), stringResource(R.string.settings_player_menu_subtitle), s.showPlayerMenuButton, { v -> update { it.copy(showPlayerMenuButton = v) } }, leadingIcon = { Icon(Icons.Rounded.MoreVert, null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_home_collage_section), icon = {}) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_collage_pattern_title), description = "",
                options = CollagePattern.entries.associate { it.storageKey to it.label },
                selectedKey = s.collagePattern,
                onSelectionChanged = { key -> update { it.copy(collagePattern = key) } },
                leadingIcon = { Icon(painterResource(R.drawable.rounded_view_column_24), null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_auto_rotate_patterns_title), stringResource(R.string.settings_auto_rotate_patterns_subtitle), s.collageAutoRotate, { v -> update { it.copy(collageAutoRotate = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_shuffle_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
        SettingsSection(title = stringResource(R.string.settings_navigation_bar_section), icon = {}) {
            ThemeSelectorItem(
                label = stringResource(R.string.settings_navbar_style_title), description = "",
                options = mapOf(
                    NavBarStyle.DEFAULT to stringResource(R.string.settings_navbar_floating),
                    NavBarStyle.FULL_WIDTH to stringResource(R.string.settings_navbar_full),
                ),
                selectedKey = s.navBarStyle,
                onSelectionChanged = { key -> update { it.copy(navBarStyle = key) } },
                leadingIcon = { Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SwitchSettingItem(stringResource(R.string.settings_compact_mode_title), stringResource(R.string.settings_compact_mode_subtitle), s.navBarCompact, { v -> update { it.copy(navBarCompact = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_view_week_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SliderSettingsItem(
                label = stringResource(R.string.settings_navbar_corner_title),
                value = s.navBarCornerRadius.toFloat(), valueRange = 0f..50f, steps = 49,
                onValueChange = { v -> update { it.copy(navBarCornerRadius = v.toInt()) } },
                valueText = { "${it.toInt()} dp" },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_default_tab_title), description = "",
                options = mapOf(
                    AppSettings.TAB_HOME to stringResource(R.string.nav_bar_home),
                    AppSettings.TAB_SEARCH to stringResource(R.string.nav_bar_search),
                    AppSettings.TAB_LIBRARY to stringResource(R.string.nav_bar_library),
                ),
                selectedKey = s.defaultTab,
                onSelectionChanged = { key -> update { it.copy(defaultTab = key) } },
                leadingIcon = { Icon(painterResource(R.drawable.tab_24), null, tint = MaterialTheme.colorScheme.secondary) },
            )
        }
        SettingsSection(title = stringResource(R.string.settings_lyrics_screen_section), icon = {}) {
            SwitchSettingItem(stringResource(R.string.settings_immersive_lyrics_title), stringResource(R.string.settings_immersive_lyrics_subtitle), s.immersiveLyrics, { v -> update { it.copy(immersiveLyrics = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) })
            SwitchSettingItem(stringResource(R.string.settings_lyrics_fallback_title), stringResource(R.string.settings_lyrics_fallback_subtitle), s.lyricsFallback, { v -> update { it.copy(lyricsFallback = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) })
        }
    }
}

@Composable
private fun PlaybackSettings(model: SpotifeurModel, navController: NavHostController) {
    val s by model.ui.settings.collectAsStateWithLifecycle()
    val update = model.ui::updateSettings
    SettingsScaffold(stringResource(R.string.settings_category_playback_title), navController) {
        val streaming = mapOf(
            fr.spotifeur.data.AudioQuality.AUTO.key to stringResource(R.string.audio_quality_auto),
            fr.spotifeur.data.AudioQuality.NORMAL.key to stringResource(R.string.audio_quality_normal),
            fr.spotifeur.data.AudioQuality.VERY_HIGH.key to stringResource(R.string.audio_quality_very_high),
        )
        ThemeSelectorItem(
            label = stringResource(R.string.settings_quality_wifi_title), description = stringResource(R.string.settings_quality_description),
            options = streaming, selectedKey = fr.spotifeur.data.AudioQuality.of(s.streamingQualityWifi).key,
            onSelectionChanged = { key -> update { it.copy(streamingQualityWifi = key) } },
            leadingIcon = { Icon(Icons.Rounded.Wifi, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        ThemeSelectorItem(
            label = stringResource(R.string.settings_quality_mobile_title), description = stringResource(R.string.settings_quality_description),
            options = streaming, selectedKey = fr.spotifeur.data.AudioQuality.of(s.streamingQualityMobile).key,
            onSelectionChanged = { key -> update { it.copy(streamingQualityMobile = key) } },
            leadingIcon = { Icon(Icons.Rounded.SignalCellularAlt, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        ThemeSelectorItem(
            label = stringResource(R.string.settings_quality_download_title), description = stringResource(R.string.settings_quality_download_description),
            options = streaming - fr.spotifeur.data.AudioQuality.AUTO.key,
            selectedKey = fr.spotifeur.data.AudioQuality.of(s.downloadQuality).takeIf { it != fr.spotifeur.data.AudioQuality.AUTO }?.key ?: fr.spotifeur.data.AudioQuality.VERY_HIGH.key,
            onSelectionChanged = { key -> update { it.copy(downloadQuality = key) } },
            leadingIcon = { Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.secondary) },
        )
        SwitchSettingItem(stringResource(R.string.settings_normalization_title), stringResource(R.string.settings_normalization_subtitle), s.audioNormalization, { v -> update { it.copy(audioNormalization = v) } }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.secondary) })
        SwitchSettingItem(stringResource(R.string.settings_keep_playing_title), stringResource(R.string.settings_keep_playing_subtitle), s.keepPlayingInBackground, { v -> update { it.copy(keepPlayingInBackground = v) } }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.secondary) })
        SwitchSettingItem(stringResource(R.string.settings_pause_on_volume_zero), stringResource(R.string.settings_pause_on_volume_zero_desc), s.pauseOnVolumeZero, { v -> update { it.copy(pauseOnVolumeZero = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_volume_down_24), null, tint = MaterialTheme.colorScheme.secondary) })
        SwitchSettingItem(stringResource(R.string.settings_headphones_resume_title), stringResource(R.string.settings_headphones_resume_subtitle), s.resumeOnHeadphones, { v -> update { it.copy(resumeOnHeadphones = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_headphones_24), null, tint = MaterialTheme.colorScheme.secondary) })
        SwitchSettingItem(stringResource(R.string.settings_persistent_shuffle_title), stringResource(R.string.settings_persistent_shuffle_subtitle), s.persistentShuffle, { v -> update { it.copy(persistentShuffle = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_shuffle_24), null, tint = MaterialTheme.colorScheme.secondary) })
        SwitchSettingItem(stringResource(R.string.settings_show_queue_history_title), stringResource(R.string.settings_show_queue_history_subtitle), s.showQueueHistory, { v -> update { it.copy(showQueueHistory = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_queue_music_24), null, tint = MaterialTheme.colorScheme.secondary) })
    }
}

@Composable
private fun BehaviorSettings(model: SpotifeurModel, navController: NavHostController) {
    val s by model.ui.settings.collectAsStateWithLifecycle()
    val update = model.ui::updateSettings
    SettingsScaffold(stringResource(R.string.settings_category_behavior_title), navController) {
        SwitchSettingItem(stringResource(R.string.settings_tap_bg_closes_title), stringResource(R.string.settings_tap_bg_closes_subtitle), s.tapBackgroundClosesPlayer, { v -> update { it.copy(tapBackgroundClosesPlayer = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_touch_app_24), null, tint = MaterialTheme.colorScheme.secondary) })
        SwitchSettingItem(stringResource(R.string.settings_haptic_feedback_title), stringResource(R.string.settings_haptic_feedback_subtitle), s.hapticsEnabled, { v -> update { it.copy(hapticsEnabled = v) } }, leadingIcon = { Icon(painterResource(R.drawable.rounded_touch_app_24), null, tint = MaterialTheme.colorScheme.secondary) })
        run {
            val swipeOptions = mapOf(
                TrackSwipeAction.NONE.key to stringResource(R.string.swipe_action_none),
                TrackSwipeAction.ADD_TO_QUEUE.key to stringResource(R.string.swipe_action_queue),
                TrackSwipeAction.PLAY_NEXT.key to stringResource(R.string.swipe_action_next),
                TrackSwipeAction.LIKE.key to stringResource(R.string.swipe_action_like),
                TrackSwipeAction.REMOVE.key to stringResource(R.string.swipe_action_remove),
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_swipe_right_title), description = stringResource(R.string.settings_swipe_right_subtitle),
                options = swipeOptions, selectedKey = TrackSwipeAction.of(s.swipeRight).key,
                onSelectionChanged = { key -> update { it.copy(swipeRight = key) } },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            ThemeSelectorItem(
                label = stringResource(R.string.settings_swipe_left_title), description = stringResource(R.string.settings_swipe_left_subtitle),
                options = swipeOptions, selectedKey = TrackSwipeAction.of(s.swipeLeft).key,
                onSelectionChanged = { key -> update { it.copy(swipeLeft = key) } },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = MaterialTheme.colorScheme.secondary) },
            )
        }
        SwitchSettingItem(stringResource(R.string.settings_search_sources_title), stringResource(R.string.settings_search_sources_subtitle), s.showSearchSources, { v -> update { it.copy(showSearchSources = v) } }, leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.secondary) })
        ThemeSelectorItem(
            label = stringResource(R.string.settings_mini_swipe_title), description = stringResource(R.string.settings_mini_swipe_subtitle),
            options = mapOf(
                AppSettings.MINI_SWIPE_TRACKS to stringResource(R.string.settings_mini_swipe_tracks),
                AppSettings.MINI_SWIPE_DISMISS to stringResource(R.string.settings_mini_swipe_dismiss),
                AppSettings.MINI_SWIPE_OFF to stringResource(R.string.settings_mini_swipe_off),
            ),
            selectedKey = s.miniPlayerSwipe,
            onSelectionChanged = { key -> update { it.copy(miniPlayerSwipe = key) } },
            leadingIcon = { Icon(Icons.Rounded.SwipeLeft, null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.1f Go", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.0f Mo", bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} Ko"
}

@Composable
private fun StorageSettings(model: SpotifeurModel, navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageBytes by remember { mutableLongStateOf(0L) }
    var downloadBytes by remember { mutableLongStateOf(0L) }
    var revision by remember { mutableStateOf(0) }
    val downloads by model.ui.downloads.collectAsStateWithLifecycle()
    val settings by model.ui.settings.collectAsStateWithLifecycle()
    var audioBytes by remember { mutableLongStateOf(0L) }
    var catalogBytes by remember { mutableLongStateOf(0L) }
    val storedDownloadBytes by model.ui.downloadsStoredBytes.collectAsStateWithLifecycle()
    LaunchedEffect(revision, downloads.size, storedDownloadBytes) {
        audioBytes = withContext(Dispatchers.IO) { model.ui.audioCacheBytes() }
        catalogBytes = withContext(Dispatchers.IO) { model.ui.catalogBytes() }
        imageBytes = withContext(Dispatchers.IO) { context.imageLoader.diskCache?.size ?: 0L }
        downloadBytes = withContext(Dispatchers.IO) { model.ui.downloadsSizeBytes() }
    }
    SettingsScaffold(stringResource(R.string.settings_storage_title), navController) {
        SettingsItem(
            title = stringResource(R.string.audio_cache_title),
            subtitle = stringResource(R.string.audio_cache_usage, formatSize(audioBytes), formatSize(settings.audioCacheLimitMb * 1024L * 1024L)),
            leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.secondary) },
            onClick = { navController.navigateSafely(Screen.SettingsCategory.createRoute("audio_cache")) },
        )
        ActionSettingsItem(
            title = stringResource(R.string.catalog_cache_title),
            subtitle = stringResource(R.string.catalog_cache_subtitle, formatSize(catalogBytes)),
            icon = { Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.common_clear),
            onPrimaryAction = {
                scope.launch {
                    withContext(Dispatchers.IO) { model.ui.clearCatalog() }
                    revision++
                }
            },
        )
        ActionSettingsItem(
            title = stringResource(R.string.settings_cache_images_title),
            subtitle = formatSize(imageBytes),
            icon = { Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.common_clear),
            onPrimaryAction = {
                scope.launch {
                    withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }
                    context.imageLoader.memoryCache?.clear()
                    revision++
                }
            },
        )
        ActionSettingsItem(
            title = stringResource(R.string.settings_cache_lyrics_title),
            subtitle = stringResource(R.string.settings_cache_lyrics_subtitle),
            icon = { Icon(painterResource(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.common_clear),
            onPrimaryAction = { model.ui.clearSavedLyrics() },
        )
        ActionSettingsItem(
            title = stringResource(R.string.library_tab_downloads),
            subtitle = stringResource(R.string.downloads_storage, downloads.size, formatSize(downloadBytes)),
            icon = { Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.common_clear),
            onPrimaryAction = { model.ui.removeAllDownloads() },
        )
    }
}

@Composable
private fun DiagnosticsSettings(model: SpotifeurModel, navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableLongStateOf(0L) }
    LaunchedEffect(revision) { report = null; report = model.diagnosticReport() }
    val copied = stringResource(R.string.diagnostics_copied)
    val chooser = stringResource(R.string.diagnostics_share_action)
    SettingsScaffold(stringResource(R.string.diagnostics_title), navController) {
        Text(stringResource(R.string.diagnostics_description), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        ActionSettingsItem(
            title = stringResource(R.string.diagnostics_send_title),
            subtitle = stringResource(R.string.diagnostics_send_subtitle),
            icon = { Icon(Icons.Rounded.BugReport, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.diagnostics_share_action),
            onPrimaryAction = {
                val text = report ?: return@ActionSettingsItem
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Spotifeur diagnostic report")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text)
                context.startActivity(android.content.Intent.createChooser(send, chooser))
            },
            secondaryActionLabel = stringResource(R.string.diagnostics_copy_action),
            onSecondaryAction = {
                val text = report ?: return@ActionSettingsItem
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Spotifeur diagnostic report", text))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            enabled = report != null,
        )
        ActionSettingsItem(
            title = stringResource(R.string.diagnostics_clear_title),
            subtitle = stringResource(R.string.diagnostics_clear_subtitle),
            icon = { Icon(Icons.Rounded.Restore, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.diagnostics_clear_action),
            onPrimaryAction = { scope.launch { model.clearDiagnostics(); revision++ } },
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(report ?: stringResource(R.string.diagnostics_loading), modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun BackupSettings(model: SpotifeurModel, navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exported = stringResource(R.string.backup_exported)
    val restored = stringResource(R.string.backup_restored)
    val failed = stringResource(R.string.backup_failed)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val content = model.ui.exportBackup()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } }
            }.isSuccess
            Toast.makeText(context, if (ok) exported else failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val content = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                model.ui.restoreBackup(checkNotNull(content))
            }.isSuccess
            Toast.makeText(context, if (ok) restored else failed, Toast.LENGTH_SHORT).show()
        }
    }
    SettingsScaffold(stringResource(R.string.settings_category_backup_title), navController) {
        ActionSettingsItem(
            title = stringResource(R.string.settings_export_backup_title),
            subtitle = stringResource(R.string.backup_export_subtitle),
            icon = { Icon(painterResource(R.drawable.outline_save_24), null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.backup_export_action),
            onPrimaryAction = { exportLauncher.launch("spotifeur-backup.json") },
        )
        ActionSettingsItem(
            title = stringResource(R.string.settings_import_backup_title),
            subtitle = stringResource(R.string.backup_import_subtitle),
            icon = { Icon(Icons.Rounded.Restore, null, tint = MaterialTheme.colorScheme.secondary) },
            primaryActionLabel = stringResource(R.string.backup_import_action),
            onPrimaryAction = { importLauncher.launch(arrayOf("application/json")) },
        )
    }
}

@Composable
private fun AccountSettings(model: SpotifeurModel, navController: NavHostController) {
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.settings_accounts_title), navController) {
        SettingsSection(title = stringResource(R.string.search_source_spotify), icon = {}) {
            if (!discovery.spotifyEnabled) SettingsItem(
                title = stringResource(R.string.settings_account_signin_title),
                subtitle = stringResource(R.string.settings_account_signin_subtitle),
                leadingIcon = { Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.secondary) },
                onClick = { model.showSignIn() },
            ) else SettingsItem(
                title = stringResource(R.string.settings_logout_title),
                subtitle = stringResource(R.string.settings_logout_subtitle),
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { model.disconnectSpotify() },
            )
        }
        SettingsSection(title = stringResource(R.string.search_source_youtube), icon = {}) {
            val account = google
            if (account == null) SettingsItem(
                title = stringResource(R.string.google_connect_title),
                subtitle = stringResource(R.string.google_connect_subtitle),
                leadingIcon = { Icon(painterResource(R.drawable.ic_source_youtube_music), null, tint = MaterialTheme.colorScheme.secondary) },
                onClick = { navController.navigateSafely(Screen.GoogleSignIn.route) },
            ) else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsItem(
                    title = account.name,
                    subtitle = account.handle.ifBlank { stringResource(R.string.google_connected_subtitle) },
                    leadingIcon = {
                        coil.compose.AsyncImage(account.photoUrl, null, Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape))
                    },
                    onClick = { model.refreshYouTubeLibrary() },
                )
                SettingsItem(
                    title = stringResource(R.string.google_disconnect_title),
                    subtitle = stringResource(R.string.google_disconnect_subtitle),
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { model.disconnectGoogle() },
                )
            }
        }
    }
}

@Composable
private fun shelfName(shelf: fr.spotifeur.data.preferences.HomeShelf): String = stringResource(when (shelf) {
    fr.spotifeur.data.preferences.HomeShelf.PINS -> R.string.home_pins
    fr.spotifeur.data.preferences.HomeShelf.RECENT -> R.string.home_recent
    fr.spotifeur.data.preferences.HomeShelf.DOWNLOADS -> R.string.home_downloads
    fr.spotifeur.data.preferences.HomeShelf.LIKED -> R.string.liked_title
    fr.spotifeur.data.preferences.HomeShelf.PLAYLISTS -> R.string.home_playlists
    fr.spotifeur.data.preferences.HomeShelf.MULTI_SERVICE -> R.string.home_multi_service
    fr.spotifeur.data.preferences.HomeShelf.SPOTIFY_PLAYLISTS -> R.string.home_spotify_playlists
    fr.spotifeur.data.preferences.HomeShelf.YOUTUBE_PLAYLISTS -> R.string.home_youtube_playlists
    fr.spotifeur.data.preferences.HomeShelf.ALBUMS -> R.string.home_albums
    fr.spotifeur.data.preferences.HomeShelf.ARTISTS -> R.string.home_artists
    fr.spotifeur.data.preferences.HomeShelf.LOCAL_FILES -> R.string.home_local_files
})

@Composable
private fun styleName(style: fr.spotifeur.data.preferences.ShelfStyle): String = stringResource(when (style) {
    fr.spotifeur.data.preferences.ShelfStyle.CAROUSEL -> R.string.shelf_style_carousel
    fr.spotifeur.data.preferences.ShelfStyle.LARGE -> R.string.shelf_style_large
    fr.spotifeur.data.preferences.ShelfStyle.GRID -> R.string.shelf_style_grid
    fr.spotifeur.data.preferences.ShelfStyle.LIST -> R.string.shelf_style_list
    fr.spotifeur.data.preferences.ShelfStyle.PILLS -> R.string.shelf_style_pills
    fr.spotifeur.data.preferences.ShelfStyle.COLLAGE -> R.string.shelf_style_collage
    fr.spotifeur.data.preferences.ShelfStyle.CARD -> R.string.shelf_style_card
})

/** Which shelves the home screen shows, in which order, with which layout and how many items. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HomeSettings(model: SpotifeurModel, navController: NavHostController) {
    val s by model.ui.settings.collectAsStateWithLifecycle()
    val shelves = s.homeLayout
    fun save(list: List<fr.spotifeur.data.preferences.ShelfConfig>) =
        model.ui.updateSettings { it.copy(homeShelves = fr.spotifeur.data.preferences.HomeLayout.encode(list)) }
    fun change(index: Int, update: (fr.spotifeur.data.preferences.ShelfConfig) -> fr.spotifeur.data.preferences.ShelfConfig) =
        save(shelves.mapIndexed { position, config -> if (position == index) update(config) else config })
    fun move(from: Int, to: Int) { if (to in shelves.indices) save(shelves.toMutableList().apply { add(to, removeAt(from)) }) }
    SettingsScaffold(stringResource(R.string.settings_home_title), navController) {
        Text(stringResource(R.string.settings_home_description), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        shelves.forEachIndexed { index, config ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shelfName(config.shelf), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        androidx.compose.material3.IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.shelf_move_up))
                        }
                        androidx.compose.material3.IconButton(onClick = { move(index, index + 1) }, enabled = index < shelves.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.shelf_move_down))
                        }
                        androidx.compose.material3.Switch(config.visible, { visible -> change(index) { it.copy(visible = visible) } })
                    }
                    if (config.visible) {
                        if (config.shelf.styles.size > 1) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            config.shelf.styles.forEach { style ->
                                androidx.compose.material3.FilterChip(selected = config.style == style, onClick = { change(index) { it.copy(style = style) } },
                                    label = { Text(styleName(style)) })
                            }
                        }
                        if (config.shelf.counts) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.shelf_items), Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelLarge)
                            fr.spotifeur.data.preferences.HomeLayout.COUNTS.forEach { count ->
                                androidx.compose.material3.FilterChip(selected = config.count == count, onClick = { change(index) { it.copy(count = count) } },
                                    label = { Text(count.toString()) })
                            }
                        }
                        if (config.style != fr.spotifeur.data.preferences.ShelfStyle.CARD && config.style != fr.spotifeur.data.preferences.ShelfStyle.COLLAGE) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.shelf_show_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            androidx.compose.material3.Switch(config.showTitle, { shown -> change(index) { it.copy(showTitle = shown) } })
                        }
                    }
                }
            }
        }
        androidx.compose.material3.TextButton(onClick = { model.ui.updateSettings { it.copy(homeShelves = "") } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.shelf_reset))
        }
    }
}

/** Which buttons album and playlist pages show in their bar, in which order; the others go to the ⋮ menu. */
@Composable
private fun PageActionsSettings(model: SpotifeurModel, navController: NavHostController) {
    val s by model.ui.settings.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.settings_pages_title), navController) {
        Text(stringResource(R.string.settings_pages_description), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(fr.spotifeur.data.preferences.DetailPage.ALBUM to R.string.settings_pages_album,
            fr.spotifeur.data.preferences.DetailPage.PLAYLIST to R.string.settings_pages_playlist).forEach { (page, title) ->
            val slots = s.actions(page)
            fun save(list: List<fr.spotifeur.data.preferences.ActionSlot>) = model.ui.updateSettings {
                val encoded = fr.spotifeur.data.preferences.DetailActions.encode(list)
                if (page == fr.spotifeur.data.preferences.DetailPage.ALBUM) it.copy(albumActions = encoded) else it.copy(playlistActions = encoded)
            }
            fun move(from: Int, to: Int) { if (to in slots.indices) save(slots.toMutableList().apply { add(to, removeAt(from)) }) }
            Text(stringResource(title), Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            slots.forEachIndexed { index, slot ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(actionName(slot.action), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(if (slot.shown) R.string.settings_pages_in_bar else R.string.settings_pages_in_menu),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.shelf_move_up))
                        }
                        androidx.compose.material3.IconButton(onClick = { move(index, index + 1) }, enabled = index < slots.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.shelf_move_down))
                        }
                        androidx.compose.material3.Switch(slot.shown, { shown -> save(slots.mapIndexed { i, it -> if (i == index) it.copy(shown = shown) else it }) })
                    }
                }
            }
        }
        androidx.compose.material3.TextButton(onClick = { model.ui.updateSettings { it.copy(albumActions = "", playlistActions = "") } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_pages_reset))
        }
    }
}
