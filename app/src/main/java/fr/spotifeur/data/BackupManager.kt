package fr.spotifeur.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.spotifeur.data.preferences.AppSettings
import fr.spotifeur.data.preferences.SettingsStore
import fr.spotifeur.playback.equalizerDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a backup holds: settings, pins, local playlists, search history and equalizer.
 * It never holds the Spotify session, which stays in Android Keystore, nor any audio.
 */
class BackupManager(
    context: Context,
    private val pins: PinnedStore,
    private val playlists: LocalPlaylistStore,
) {
    private val app = context.applicationContext
    private val settings = SettingsStore.get(app)

    suspend fun export(searchHistory: List<Music>): String {
        val equalizer = JSONObject()
        app.equalizerDataStore.data.first().asMap().forEach { (key, value) ->
            equalizer.put(key.name, JSONObject().put("type", value::class.java.simpleName).put("value", value.toString()))
        }
        return JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("settings", settings.settings.value.toJson())
            .put("pins", JSONArray(encodePins(pins.pins.value)))
            .put("localPlaylists", JSONArray(encodeLocalPlaylists(playlists.playlists.value)))
            .put("searchHistory", JSONArray(encodePins(searchHistory)))
            .put("equalizer", equalizer)
            .toString(2)
    }

    /** Validates the whole file before changing anything. Returns the search history to restore. */
    suspend fun restore(content: String): List<Music> {
        val root = JSONObject(content)
        require(root.optString("format") == FORMAT) { "Not a Spotifeur backup" }
        val restoredSettings = AppSettings.fromJson(root.getJSONObject("settings"))
        val restoredPins = decodePins(root.getJSONArray("pins").toString())
        val restoredPlaylists = decodeLocalPlaylists(root.getJSONArray("localPlaylists").toString())
        // Older backups hold typed queries (strings): only result entries are kept.
        val history = root.optJSONArray("searchHistory")?.let { list ->
            decodePins(JSONArray((0 until list.length()).mapNotNull { list.optJSONObject(it) }).toString())
        }.orEmpty()
        settings.update { restoredSettings }
        pins.replaceAll(restoredPins)
        playlists.replaceAll(restoredPlaylists)
        root.optJSONObject("equalizer")?.let { equalizer ->
            app.equalizerDataStore.edit { store ->
                store.clear()
                equalizer.keys().forEach { name ->
                    val entry = equalizer.getJSONObject(name)
                    val value = entry.getString("value")
                    when (entry.getString("type")) {
                        "Boolean" -> store[booleanPreferencesKey(name)] = value.toBoolean()
                        "Integer" -> store[intPreferencesKey(name)] = value.toInt()
                        "Long" -> store[longPreferencesKey(name)] = value.toLong()
                        "Float" -> store[floatPreferencesKey(name)] = value.toFloat()
                        "String" -> store[stringPreferencesKey(name)] = value
                    }
                }
            }
        }
        return history
    }

    private companion object {
        const val FORMAT = "spotifeur-backup"
        const val VERSION = 1
    }
}
