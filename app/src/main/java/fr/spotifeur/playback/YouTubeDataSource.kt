package fr.spotifeur.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import fr.spotifeur.data.AudioFileRecord
import fr.spotifeur.data.AudioIndex
import fr.spotifeur.data.Music
import fr.spotifeur.data.YOUTUBE_FILE_PREFIX
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.skibidisong.Skibidisong
import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import fr.spotifeur.data.youtube.YouTubeFailure
import fr.spotifeur.data.youtube.YouTubeMusic
import fr.spotifeur.data.youtube.YouTubeStream
import fr.spotifeur.data.youtube.youTubeVideoId
import java.io.IOException

/**
 * Addresses of tracks read from YouTube Music: `ytm://track/<videoId>` for a YouTube Music track,
 * `ytm://match/<id>?…` for a Spotify track played without a Spotify account, found by [TrackMatcher].
 */
internal object YouTubeUris {
    const val SCHEME = "ytm"

    fun isYouTube(uri: Uri?): Boolean = uri?.scheme == SCHEME

    /**
     * The address that plays [track] from YouTube Music, or null when it plays another way. Spotify
     * tracks go through YouTube Music without a Spotify account, and in Skibidisong mode.
     * [revision] changes the address of the same track, so the player reads it again.
     */
    fun of(
        track: Music,
        revision: Int = 0,
        spotifyThroughYouTube: Boolean = !SpotifyAccount.signedIn || Skibidisong.isActive,
    ): Uri? {
        track.youTubeVideoId?.let { return Uri.parse("$SCHEME://track/$it") }
        if (track.isLocalTrack || !track.id.startsWith("spotify:track:") || !spotifyThroughYouTube) return null
        return Uri.Builder().scheme(SCHEME).authority("match").appendPath(track.id)
            .appendQueryParameter("title", track.title).appendQueryParameter("artists", track.subtitle)
            .appendQueryParameter("duration", track.durationMs.toString()).appendQueryParameter("album", track.album)
            .apply { if (revision > 0) appendQueryParameter("r", revision.toString()) }
            .build()
    }

    /** The file name the caches know this track's audio by. */
    fun fileHex(trackId: String): String =
        YOUTUBE_FILE_PREFIX + (trackId.substringAfter("ytm:track:", "").ifEmpty { "sp-" + trackId.substringAfterLast(':') })

    @androidx.annotation.OptIn(UnstableApi::class)
    fun cacheKey(trackId: String): String = AudioCache.key(fileHex(trackId))

    /** The track id an address plays, as the queue and the indexes know it. */
    fun trackId(uri: Uri): String? = when (uri.authority) {
        "track" -> uri.lastPathSegment?.let { "ytm:track:$it" }
        "match" -> uri.lastPathSegment
        else -> null
    }

    fun matchQuery(uri: Uri): MatchQuery? {
        if (uri.authority != "match") return null
        val id = uri.lastPathSegment ?: return null
        return MatchQuery(
            key = id, title = uri.getQueryParameter("title").orEmpty(), artists = uri.getQueryParameter("artists").orEmpty(),
            durationMs = uri.getQueryParameter("duration")?.toIntOrNull() ?: 0, album = uri.getQueryParameter("album").orEmpty(),
        )
    }
}

/**
 * Reads a YouTube Music audio stream: resolves its address (once per few hours), then reads it in
 * bounded ranges, since the CDN throttles or refuses open-ended requests. A refused address is
 * resolved again through another client.
 */
@UnstableApi
internal class YouTubeDataSource private constructor(private val context: Context) : BaseDataSource(true) {
    class Factory(context: Context) : DataSource.Factory {
        private val app = context.applicationContext
        init { YouTubeMusic.init(app); TrackMatcher.init(app) }
        override fun createDataSource(): DataSource = YouTubeDataSource(app)
    }

    private var delegate: HttpDataSource? = null
    private var stream: YouTubeStream? = null
    private var spec: DataSpec? = null
    private var position = 0L
    private var requestEnd = UNSET
    private var resourceEnd = UNSET
    private var bytesLeftInRange = 0L
    private var rangeYielded = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val trackId = YouTubeUris.trackId(uri) ?: throw IOException("Unknown YouTube Music address.")
        val videoId = when (uri.authority) {
            "track" -> checkNotNull(uri.lastPathSegment)
            else -> YouTubeUris.matchQuery(uri)?.let { query ->
                if (Skibidisong.isActive) Skibidisong.videoFor(query)
                else Skibidisong.knownVideo(query.key) ?: TrackMatcher.resolve(query)
            } ?: throw YouTubeFailure("This track was not found on YouTube Music.", "NO_MATCH")
        }
        position = dataSpec.position
        requestEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else UNSET
        val excluded = HashSet<String>()
        var failure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val current = YouTubeMusic.stream(videoId, excluded)
            stream = current
            resourceEnd = if (current.contentLength > 0) current.contentLength else UNSET
            if (resourceEnd != UNSET && position > resourceEnd) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            try {
                delegate = OkHttpDataSource.Factory(YouTubeMusic.http).setUserAgent(current.userAgent).createDataSource()
                openNextRange()
                if (resourceEnd != UNSET) AudioIndex.get(context).attachDirectFile(trackId, AudioFileRecord(YouTubeUris.fileHex(trackId), resourceEnd, ""))
                opened = true
                transferStarted(dataSpec)
                val end = knownEnd()
                return if (end == UNSET) C.LENGTH_UNSET.toLong() else end - dataSpec.position
            } catch (refused: HttpDataSource.InvalidResponseCodeException) {
                if (refused.responseCode !in REFUSED) throw refused
                delegate?.close(); delegate = null
                YouTubeMusic.forgetStream(videoId)
                excluded += current.client
                if (attempt == 0) YouTubeMusic.renewIdentity()
                failure = refused
            }
        }
        throw failure ?: YouTubeFailure("The audio of this track could not be opened.")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val source = delegate ?: return C.RESULT_END_OF_INPUT
        if (bytesLeftInRange <= 0L) {
            val end = knownEnd()
            if (end != UNSET && position >= end) return C.RESULT_END_OF_INPUT
            source.close()
            openNextRange()
            if (bytesLeftInRange <= 0L) return C.RESULT_END_OF_INPUT
        }
        val read = source.read(buffer, offset, minOf(length.toLong(), bytesLeftInRange).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            // The range ended early: open the next one from where reading stopped.
            if (!rangeYielded) return C.RESULT_END_OF_INPUT
            bytesLeftInRange = 0L
            return read(buffer, offset, length)
        }
        rangeYielded = true
        position += read
        bytesLeftInRange -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        bytesLeftInRange = 0L
        try { delegate?.close() } finally {
            delegate = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    private fun knownEnd(): Long = when {
        requestEnd != UNSET && resourceEnd != UNSET -> minOf(requestEnd, resourceEnd)
        requestEnd != UNSET -> requestEnd
        else -> resourceEnd
    }

    private fun openNextRange() {
        val source = checkNotNull(delegate)
        val address = checkNotNull(stream).url
        val end = knownEnd()
        val length = minOf(RANGE_BYTES, if (end != UNSET) end - position else Long.MAX_VALUE)
        rangeYielded = false
        if (length <= 0L) { bytesLeftInRange = 0L; return }
        try {
            source.open(DataSpec.Builder().setUri(address).setPosition(position).setLength(length).build())
        } catch (failure: HttpDataSource.InvalidResponseCodeException) {
            if (failure.responseCode == 416) { bytesLeftInRange = 0L; return }
            throw failure
        }
        val served = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            ?.let { CONTENT_RANGE.find(it) }
        if (served != null) {
            val (_, last, total) = served.destructured
            bytesLeftInRange = last.toLong() - position + 1
            total.toLongOrNull()?.let { resourceEnd = it }
        } else bytesLeftInRange = length
    }

    private companion object {
        /** The CDN serves ranges of about a megabyte at full speed and slows down larger ones. */
        const val RANGE_BYTES = 900_000L
        const val MAX_ATTEMPTS = 3
        val UNSET = C.LENGTH_UNSET.toLong()
        val REFUSED = setOf(401, 403, 404, 410)
        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
    }
}

/** Sends every address to the source that reads it: YouTube Music, or the Spotify chain. */
@UnstableApi
internal class RoutingDataSourceFactory(
    private val spotify: DataSource.Factory,
    private val youtube: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(spotify, youtube)
}

@UnstableApi
private class RoutingDataSource(
    private val spotifyFactory: DataSource.Factory,
    private val youtubeFactory: DataSource.Factory,
) : DataSource {
    private val listeners = ArrayList<androidx.media3.datasource.TransferListener>()
    private var current: DataSource? = null
    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        val source = (if (YouTubeUris.isYouTube(dataSpec.uri)) youtubeFactory else spotifyFactory).createDataSource()
        listeners.forEach(source::addTransferListener)
        current = source
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(current).read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()
    override fun close() { try { current?.close() } finally { current = null } }
}
