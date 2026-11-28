package fr.spotifeur.data.skibidisong

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

/** How a Spotify track's YouTube Music recording was chosen. */
enum class MatchMethod(val code: Int) {
    /** By title, artist and duration only: the audio was not compared yet. */
    Text(0),
    /** The start of both recordings overlaps. */
    Audio(1),
    /** No candidate overlapped: the first search result with the same title and artist was kept. */
    Fallback(2),
    /** Not on YouTube Music: no result has the title and artist. The track is skipped. */
    Missing(3);

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Text }
}

data class SkibidisongMatch(
    val trackId: String,
    val videoId: String,
    val method: MatchMethod,
    /** Bit error rate of the audio comparison, when one was made. */
    val errorRate: Double? = null,
    /** Where the Spotify excerpt starts in the YouTube Music recording. */
    val lagMs: Long? = null,
    val checkedAt: Long = System.currentTimeMillis(),
)

/**
 * Spotify track → YouTube Music recording, kept in its own database so every match found once is
 * reused, whichever playlist or queue the track comes back in. Read from memory after the first load.
 */
internal class SkibidisongStore(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {
    private val memory = ConcurrentHashMap<String, SkibidisongMatch>()
    @Volatile private var loaded = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE matches (track TEXT PRIMARY KEY NOT NULL, video TEXT NOT NULL, method INTEGER NOT NULL, " +
            "error_rate REAL, lag_ms INTEGER, checked_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            readableDatabase.rawQuery("SELECT track, video, method, error_rate, lag_ms, checked_at FROM matches", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val match = SkibidisongMatch(cursor.getString(0), cursor.getString(1), MatchMethod.of(cursor.getInt(2)),
                        if (cursor.isNull(3)) null else cursor.getDouble(3), if (cursor.isNull(4)) null else cursor.getLong(4), cursor.getLong(5))
                    memory[match.trackId] = match
                }
            }
            loaded = true
        }
    }

    fun get(trackId: String): SkibidisongMatch? { load(); return memory[trackId] }

    fun put(match: SkibidisongMatch) {
        load()
        memory[match.trackId] = match
        writableDatabase.insertWithOnConflict("matches", null, ContentValues().apply {
            put("track", match.trackId); put("video", match.videoId); put("method", match.method.code)
            put("error_rate", match.errorRate); put("lag_ms", match.lagMs); put("checked_at", match.checkedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun count(): Int { load(); return memory.size }

    fun all(): List<SkibidisongMatch> { load(); return memory.values.toList() }

    fun countBy(method: MatchMethod): Int { load(); return memory.values.count { it.method == method } }

    fun clear() {
        load()
        memory.clear()
        writableDatabase.delete("matches", null, null)
    }

    private companion object {
        const val NAME = "skibidisong.db"
        const val VERSION = 1
    }
}
