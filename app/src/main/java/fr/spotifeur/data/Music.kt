package fr.spotifeur.data

enum class MusicKind(val label: String) {
    Track("Track"), Album("Album"), Playlist("Playlist"), Artist("Artist"), Radio("Radio")
}

data class Music(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: MusicKind,
    val artwork: Int = 0,
    val imageUrl: String? = null,
    val audio: Int? = null,
    val durationMs: Int = 0,
    val tracks: List<String> = emptyList(),
    val genre: String = "",
    val note: String = "",
    val album: String = "",
    /** Playable location of a local file; Spotify tracks resolve theirs from [id]. */
    val mediaUri: String? = null,
    /** Known audio format, shown when the quality setting is on. */
    val quality: String = "",
    val ownerUsername: String? = null,
)

/** Where a track's audio comes from. */
enum class MusicSource { Local, Spotify, YouTubeMusic }

val Music.source: MusicSource get() = musicSourceOf(id)

fun musicSourceOf(id: String): MusicSource = when {
    isLocalTrackId(id) -> MusicSource.Local
    id.startsWith(fr.spotifeur.data.youtube.YTM_TRACK_PREFIX) || id.startsWith(fr.spotifeur.data.youtube.YTM_ALBUM_PREFIX) ||
        id.startsWith(fr.spotifeur.data.youtube.YTM_ARTIST_PREFIX) -> MusicSource.YouTubeMusic
    else -> MusicSource.Spotify
}
