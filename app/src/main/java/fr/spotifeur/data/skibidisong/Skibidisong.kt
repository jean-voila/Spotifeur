package fr.spotifeur.data.skibidisong

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import fr.spotifeur.BuildConfig
import fr.spotifeur.data.Music
import fr.spotifeur.data.NetworkMonitor
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.SpotifyAndroid
import fr.spotifeur.data.SpotifyAuthRequired
import fr.spotifeur.data.SpotifyHlsDataSource
import fr.spotifeur.data.SpotifySession
import fr.spotifeur.data.diagnostics.DiagnosticLog
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.data.preferences.SettingsStore
import fr.spotifeur.data.spotifyAttributes
import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import fr.spotifeur.data.youtube.YouTubeMusic
import fr.spotifeur.data.youtube.youTubeVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Skibidisong mode: on a Spotify account whose encrypted audio the engine refuses (playback stops
 * after the clear lead-in), every Spotify track plays its YouTube Music recording instead.
 *
 * A match is chosen by comparing the start of both recordings: the Spotify lead-in is decoded, then
 * slid over the first seconds of each YouTube Music candidate. The track being played is matched
 * first, then the queue in order, then the rest of the context in the background. Matches are kept in
 * [SkibidisongStore].
 */
@androidx.annotation.OptIn(UnstableApi::class)
object Skibidisong {
    private const val TAG = "Skibidisong"
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { SkibidisongStore(checkNotNull(app)) }
    private val preferences get() = checkNotNull(app).getSharedPreferences("skibidisong", Context.MODE_PRIVATE)

    private val restricted = MutableStateFlow(false)
    private val mutableActive = MutableStateFlow(false)
    /** True while Spotify tracks are read from YouTube Music on a signed-in, restricted account. */
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    val isActive: Boolean get() = mutableActive.value
    /** Whether the current account is restricted, whatever the setting. */
    val accountRestricted: StateFlow<Boolean> = restricted.asStateFlow()
    private val mutableCorrections = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Tracks whose recording changed after it may already have been heard. */
    val corrections: SharedFlow<String> = mutableCorrections.asSharedFlow()

    @Volatile private var accountKey: String? = null

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        // Read the matches once, off the main thread, before the player asks for them.
        scope.launch { store.count() }
        scope.launch { SettingsStore.get(context).settings.collect { updateActive() } }
        scope.launch { restricted.collect { updateActive() } }
        SpotifyAccount.onSessionReplaced {
            if (!SpotifyAccount.signedIn) { accountKey = null; restricted.value = false }
            updateActive()
        }
        repeat(BACKGROUND_WORKERS) { worker -> scope.launch { background(worker) } }
    }

    private fun updateActive() {
        val context = app ?: return
        val enabled = SettingsStore.get(context).settings.value.skibidisong
        val now = enabled && restricted.value && SpotifyAccount.signedIn
        if (mutableActive.value != now) {
            mutableActive.value = now
            Log.i(TAG, "active=$now (setting=$enabled restricted=${restricted.value} signedIn=${SpotifyAccount.signedIn})")
            if (now) scope.launch { Log.i(TAG, "store: ${summary()}") }
            DiagnosticLog.record("skibidisong", "active=$now restricted=${restricted.value}")
            planRevision.value++
        }
    }

    // Account ---------------------------------------------------------------------------------------

    /** Free plan without offline or on-demand playback: the engine stops these at the encrypted boundary. */
    internal fun isRestricted(attributes: Map<String, String>): Boolean =
        attributes["type"] != "premium" && (attributes["offline"] == "false" || attributes["on-demand"] == "false")

    private fun signature(attributes: Map<String, String>) =
        listOf("type", "offline", "on-demand", "ads").joinToString("|") { attributes[it].orEmpty() }

    /** Reads the account's plan attributes; the last known answer applies until then, and offline. */
    internal suspend fun refreshAccount(session: SpotifySession) {
        val key = runCatching { sha256(session.username()) }.getOrNull() ?: return
        accountKey = key
        restricted.value = preferences.getBoolean("$key:restricted", false)
        updateActive()
        if (!NetworkMonitor.online.value) return
        val attributes = try { spotifyAttributes(SpotifyAccount.transport, session, "Skibidisong account check") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.w(TAG, "account attributes unavailable: ${failure.message}"); return }
        if (accountKey != key) return
        val signature = signature(attributes)
        val refused = preferences.getString("$key:refusedFor", null) == signature
        val value = isRestricted(attributes) || refused
        preferences.edit { putBoolean("$key:restricted", value).putString("$key:signature", signature) }
        restricted.value = value
        Log.i(TAG, "account $signature restricted=$value (refused before=$refused)")
    }

    /** The engine refused this account's encrypted audio: it is restricted, whatever its attributes say. */
    fun reportRefusal() {
        val key = accountKey ?: return
        if (restricted.value) return
        Log.i(TAG, "native refusal: account marked restricted")
        preferences.edit { putBoolean("$key:restricted", true).putString("$key:refusedFor", preferences.getString("$key:signature", "?")) }
        restricted.value = true
    }

    // Lookups -----------------------------------------------------------------------------------------

    fun knownVideo(trackId: String): String? = if (app == null) null else store.get(trackId)?.takeIf { it.method != MatchMethod.Missing }?.videoId

    fun matchOf(trackId: String): SkibidisongMatch? = if (app == null) null else store.get(trackId)

    fun count(): Int = if (app == null) 0 else store.count()

    internal fun matches(): List<SkibidisongMatch> = if (app == null) emptyList() else store.all()

    /** One line for logs and the diagnostic report. */
    fun summary(): String {
        if (app == null) return "not started"
        val all = store.all()
        val audio = all.filter { it.method == MatchMethod.Audio }
        val rates = audio.mapNotNull { it.errorRate }.sorted()
        fun percentile(p: Double) = rates.getOrNull(((rates.size - 1) * p).toInt())?.let { "%.3f".format(it) } ?: "-"
        return "matches=${all.size} audio=${audio.size} fallback=${all.count { it.method == MatchMethod.Fallback }} " +
            "missing=${all.count { it.method == MatchMethod.Missing }} " +
            "text=${all.count { it.method == MatchMethod.Text }} ber p50=${percentile(0.5)} p90=${percentile(0.9)} p99=${percentile(0.99)} max=${rates.lastOrNull()?.let { "%.3f".format(it) } ?: "-"}"
    }

    fun clear() {
        if (app == null) return
        store.clear()
        Log.i(TAG, "matches cleared")
        planRevision.value++
    }

    /**
     * Blocking, for the player's loading thread: the YouTube Music recording of a Spotify track. A
     * stored match answers at once; otherwise the track is matched now, ahead of background work.
     * Another track (a read-ahead of the queue) first lets the current one finish.
     */
    fun videoFor(query: MatchQuery): String? {
        store.get(query.key)?.let { stored ->
            if (stored.method == MatchMethod.Text && canCheckAudio()) planRevision.value++
            return stored.videoId.takeIf { stored.method != MatchMethod.Missing }
        }
        val started = SystemClock.elapsedRealtime()
        urgent.incrementAndGet()
        try {
            val match = runBlocking {
                val current = currentId
                if (current != null && current != query.key) {
                    synchronized(running) { running[current] }?.let { first -> withTimeoutOrNull(CURRENT_FIRST_MS) { runCatching { first.job.await() } } }
                    store.get(query.key)?.let { return@runBlocking it }
                }
                matchJob(query, audio = canCheckAudio(), urgent = true).await()
            }
            Log.i(TAG, "player waited ${SystemClock.elapsedRealtime() - started} ms for \"${query.title}\" → ${match?.videoId} (${match?.method})" +
                if (query.key == currentId) " [current]" else "")
            return match?.takeIf { it.method != MatchMethod.Missing }?.videoId
        } finally { urgent.decrementAndGet() }
    }

    /** Instrumented tests: what the account's attributes would have said, before any refusal. */
    internal fun setRestrictedForTest(value: Boolean) { restricted.value = value }

    /** Instrumented tests: matches [query] again by ear, whatever is stored. */
    internal fun matchAgain(query: MatchQuery): SkibidisongMatch? = runBlocking { matchJob(query, audio = true, urgent = true).await() }

    private fun canCheckAudio() = SpotifyAccount.signedIn && NetworkMonitor.online.value

    // Priorities --------------------------------------------------------------------------------------

    @Volatile private var plan: List<Music> = emptyList()
    @Volatile private var currentId: String? = null
    private val planRevision = MutableStateFlow(0)
    private val urgent = AtomicInteger()
    private val running = HashMap<String, Task>()
    private val retryAfter = ConcurrentHashMap<String, Long>()

    /** A match being made; it becomes urgent once the player waits for it. */
    private class Task(urgent: Boolean) {
        @Volatile var urgent = urgent
            private set
        lateinit var job: Deferred<SkibidisongMatch?>
        fun hurry() { urgent = true }
    }

    /**
     * What the player holds, in the order it will need it: the current track, the user's queue and
     * the rest of the context, then what was already played.
     */
    fun focus(current: Music?, following: List<Music>) {
        if (!isActive) return
        val tracks = (listOfNotNull(current) + following).filter { it.id.startsWith("spotify:track:") && !it.isLocalTrack }
            .distinctBy(Music::id)
        currentId = current?.id
        plan = tracks
        planRevision.value++
        if (current != null) synchronized(running) { running[current.id] }?.hurry()
        val unknown = tracks.count { store.get(it.id) == null }
        Log.d(TAG, "focus current=\"${current?.title}\" planned=${tracks.size} unknown=$unknown")
    }

    @Volatile private var browsing: List<Music> = emptyList()

    /** Tracks on screen: matched after the queue, before the rest of the context. */
    fun browse(tracks: List<Music>) {
        val spotify = tracks.filter { it.id.startsWith("spotify:track:") && !it.isLocalTrack }
        if (spotify.map(Music::id) == browsing.map(Music::id)) return
        browsing = spotify
        if (!isActive) return
        planRevision.value++
        Log.d(TAG, "browse ${spotify.size} tracks on screen, unknown=${spotify.count { store.get(it.id) == null }}")
    }

    private fun matchJob(query: MatchQuery, audio: Boolean, urgent: Boolean): Deferred<SkibidisongMatch?> = synchronized(running) {
        running[query.key]?.takeIf { it.job.isActive }?.let { task ->
            if (urgent) task.hurry()
            return task.job
        }
        val task = Task(urgent)
        task.job = scope.async(start = CoroutineStart.LAZY) { match(query, audio, task) }
        running[query.key] = task
        task.job.invokeOnCompletion { synchronized(running) { if (running[query.key] === task) running.remove(query.key) } }
        task.job.start()
        task.job
    }

    /** Next track the background should match: by plan order, an unknown one or a text match to check. */
    private fun nextTask(): Pair<MatchQuery, Boolean>? {
        if (!isActive || !NetworkMonitor.online.value) return null
        val context = app ?: return null
        val settings = SettingsStore.get(context).settings.value
        val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        val now = SystemClock.elapsedRealtime()
        val planned = plan
        // The queue, then what is on screen, then the rest of the context.
        // By ear: the queue always, what is on screen unless mobile data is saved, the rest on Wi-Fi only.
        // On mobile data the rest is matched by text and checked once it reaches the queue.
        val order = planned.asSequence().take(QUEUE_AHEAD).map { it to true } +
            browsing.asSequence().map { it to (!metered || settings.preloadOnMobile) } +
            planned.asSequence().drop(QUEUE_AHEAD).map { it to !metered }
        synchronized(running) {
            for ((track, audio) in order) {
                if (running[track.id]?.job?.isActive == true) continue
                if ((retryAfter[track.id] ?: 0) > now) continue
                val stored = store.get(track.id)
                // Tracks without an audio match are looked for again after a while (the catalogue grows), and
                // at once when they were decided by older rules.
                val uncertain = stored?.method == MatchMethod.Missing || stored?.method == MatchMethod.Fallback
                val again = uncertain && audio && (System.currentTimeMillis() - stored!!.checkedAt > RECHECK_MS || stored.checkedAt < RULES_CHANGED_AT)
                if (stored != null && !again && (stored.method != MatchMethod.Text || !audio)) continue
                return queryOf(track) to audio
            }
        }
        return null
    }

    private suspend fun background(worker: Int) {
        while (true) {
            val seen = planRevision.value
            while (urgent.get() > 0) delay(150)
            YouTubeMusic.cooldownMs().takeIf { it > 0 }?.let { delay(it) }
            val task = nextTask()
            if (task == null) {
                withTimeoutOrNull(IDLE_WAIT_MS) { planRevision.first { it != seen } }
                continue
            }
            val (query, audio) = task
            try { matchJob(query, audio, urgent = query.key == currentId).await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                retryAfter[query.key] = SystemClock.elapsedRealtime() + RETRY_MS
                Log.w(TAG, "background[$worker] \"${query.title}\" failed: ${failure.javaClass.simpleName} ${failure.message}")
                if (failure is SpotifyAuthRequired) delay(RETRY_MS)
            }
            delay(BACKGROUND_GAP_MS)
        }
    }

    // Matching ----------------------------------------------------------------------------------------

    /**
     * Search and Spotify lead-in run together; the first candidate's excerpt starts as soon as the
     * search answers, the others only if it does not match. Reads are detached from the
     * result: a match returns as soon as it is decided, without waiting for reads it no longer needs.
     */
    private suspend fun match(query: MatchQuery, audio: Boolean, task: Task): SkibidisongMatch? {
        val started = SystemClock.elapsedRealtime()
        val tag = { if (task.urgent) "now" else "bg" }
        val lead = if (audio) scope.async {
            try { timed("${tag()} spotify lead-in \"${query.title}\"") { spotifyPrint(query.key, task) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w(TAG, "${tag()} lead-in failed \"${query.title}\": ${failure.javaClass.simpleName} ${failure.message}"); null }
        } else null
        val ranked = try { timed("${tag()} search \"${query.title}\"") { TrackMatcher.rankedCandidates(query) } }
        catch (failure: Exception) { lead?.cancel(); throw failure }
        val candidates = ranked.take(CANDIDATES)
        Log.d(TAG, "${tag()} candidates \"${query.title}\" (${query.durationMs / 1000} s): " + candidates.joinToString { describe(it) })
        val seen = LinkedHashMap<String, Pair<Music, Double>>().apply { ranked.forEach { put(it.first.id, it) } }
        // Text only (mobile data, or no lead-in): the first plausible result, checked by ear once it reaches the queue.
        fun byText(): SkibidisongMatch? {
            val choice = fallbackChoice(query, ranked, seen.values) ?: ranked.firstOrNull()?.first ?: return null
            return save(query, SkibidisongMatch(query.key, choice.youTubeVideoId!!, MatchMethod.Text), started, tag())
        }
        if (lead == null) return byText()
        val prints = candidates.map { (candidate, _) -> excerpt(candidate, task, tag) }
        try {
            prints.firstOrNull()?.start()
            val spotify = lead.await() ?: return byText()
            // A lead-in too quiet to compare still gets the wider searches, so the choice below sees every plausible result.
            val audible = spotify.activeFrames >= AudioFingerprint.MIN_OVERLAP_FRAMES
            if (!audible) Log.i(TAG, "${tag()} lead-in of \"${query.title}\" too quiet to compare (${spotify.activeFrames} frames)")
            if (audible) judge(query, spotify, candidates, prints, tag())?.let { (candidate, comparison) ->
                return save(query, SkibidisongMatch(query.key, candidate.youTubeVideoId!!, MatchMethod.Audio, comparison.errorRate, comparison.lagMs), started, tag())
            }
            // Wider searches for the exact recording, until one is heard: the version words with every artist
            // (the performer in classical music), the work and its performers, the title without its version
            // words, the videos shelf (uploads the songs shelf leaves out), and the album.
            val tried = candidates.mapTo(HashSet()) { it.first.id }
            val done = hashSetOf(TrackMatcher.searchText(query).lowercase() to false)
            val searches = listOfNotNull(
                TrackMatcher.alternateSearchText(query) to false,
                TrackMatcher.workSearchText(query)?.let { it to false },
                TrackMatcher.coreSearchText(query) to false,
                TrackMatcher.searchText(query) to true,
                TrackMatcher.albumSearchText(query)?.let { it to false },
            )
            for ((text, videos) in searches) {
                if (tried.size >= MAX_HEARD || !done.add(text.lowercase() to videos)) continue
                val found = try { TrackMatcher.rankedCandidates(query, text, videosOnly = videos) } catch (failure: IOException) { emptyList() }
                found.forEach { seen.putIfAbsent(it.first.id, it) }
                val fresh = found.filter { it.first.id !in tried && it.second > 0 }.take(SECOND_SEARCH_CANDIDATES)
                Log.d(TAG, "${tag()} wider search${if (videos) " (videos)" else ""} \"$text\": " + fresh.joinToString { describe(it) })
                if (fresh.isEmpty() || !audible) continue
                fresh.forEach { tried += it.first.id }
                val morePrints = fresh.map { (candidate, _) -> excerpt(candidate, task, tag) }
                morePrints.forEach { it.start() }
                try {
                    judge(query, spotify, fresh, morePrints, tag())?.let { (candidate, comparison) ->
                        return save(query, SkibidisongMatch(query.key, candidate.youTubeVideoId!!, MatchMethod.Audio, comparison.errorRate, comparison.lagMs), started, tag())
                    }
                } finally { morePrints.forEach { it.cancel() } }
            }
            val choice = fallbackChoice(query, ranked, seen.values)
                ?: return save(query, SkibidisongMatch(query.key, "", MatchMethod.Missing), started, tag())
            return save(query, SkibidisongMatch(query.key, choice.youTubeVideoId!!, MatchMethod.Fallback), started, tag())
        } finally { prints.forEach { it.cancel() } }
    }

    private fun fallbackChoice(query: MatchQuery, first: List<Pair<Music, Double>>, all: Collection<Pair<Music, Double>>): Music? =
        TrackMatcher.fallbackChoice(query, first, all) { it.youTubeVideoId !in unplayable }

    /** Videos that cannot be played without an account (age or sign-in checks), or at all. */
    private val unplayable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun describe(candidate: Pair<Music, Double>) = "${candidate.first.youTubeVideoId}:${"%.2f".format(candidate.second)} " +
        "\"${candidate.first.title}\" / ${candidate.first.subtitle} (${candidate.first.durationMs / 1000} s)"

    private fun excerpt(candidate: Music, task: Task, tag: () -> String): Deferred<AudioFingerprint?> = scope.async(start = CoroutineStart.LAZY) {
        try { timed("${tag()} youtube excerpt ${candidate.youTubeVideoId}") { youTubePrint(candidate.youTubeVideoId!!, task) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if ((failure as? fr.spotifeur.data.youtube.YouTubeFailure)?.status in UNPLAYABLE_STATUSES) candidate.youTubeVideoId?.let(unplayable::add)
            Log.w(TAG, "${tag()} excerpt ${candidate.youTubeVideoId} failed: ${failure.javaClass.simpleName} " +
                "${(failure as? fr.spotifeur.data.youtube.YouTubeFailure)?.status} ${failure.message}")
            null
        }
    }

    /**
     * Candidates are heard in search order; a clear overlap wins at once. A weaker one (another master,
     * or a remix built on the same recording) lets the other candidates be heard too, and the closest wins.
     * Another master of the same song can stay just above the threshold: aligned within a second and with
     * the same title and artist, it still counts.
     */
    private suspend fun judge(
        query: MatchQuery, spotify: AudioFingerprint, candidates: List<Pair<Music, Double>>,
        prints: List<Deferred<AudioFingerprint?>>, tag: String,
    ): Pair<Music, AudioFingerprint.Comparison>? {
        var best: Pair<Music, AudioFingerprint.Comparison>? = null
        for ((index, candidate) in candidates.withIndex()) {
            // After a miss or a weak match on the first candidate, the others are read together.
            if (index == 1) prints.forEach { it.start() }
            val print = prints[index].await() ?: continue
            val comparison = spotify.compare(print)
            val sameSong = comparison != null && !comparison.matches() && comparison.errorRate <= SAME_SONG_ERROR_RATE &&
                kotlin.math.abs(comparison.lagMs) <= SAME_SONG_LAG_MS && comparison.frames >= SAME_SONG_FRAMES &&
                TrackMatcher.sameSong(query, candidate.first)
            Log.i(TAG, "$tag compare \"${query.title}\" ↔ ${describe(candidate)}: " +
                "ber=${comparison?.errorRate?.let { "%.3f".format(it) }} mean=${comparison?.meanErrorRate?.let { "%.3f".format(it) }} " +
                "lag=${comparison?.lagMs}ms frames=${comparison?.frames}${if (sameSong) " same song, other master" else ""}")
            if (comparison == null || !(comparison.matches() || sameSong)) continue
            if (comparison.errorRate <= STRONG_MATCH) return candidate.first to comparison
            if (best == null || comparison.errorRate < best.second.errorRate) best = candidate.first to comparison
        }
        return best
    }

    private fun save(query: MatchQuery, match: SkibidisongMatch, started: Long, tag: String): SkibidisongMatch {
        val previous = store.get(match.trackId)
        store.put(match)
        retryAfter.remove(match.trackId)
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "$tag matched \"${query.title}\" / ${query.artists} → ${match.videoId} ${match.method} in $elapsed ms" +
            (previous?.takeIf { it.videoId != match.videoId }?.let { " (was ${it.videoId})" } ?: ""))
        DiagnosticLog.record("skibidisong", "${match.method} ${elapsed}ms ber=${match.errorRate?.let { "%.2f".format(it) }}")
        if (store.count() % 25 == 0) Log.i(TAG, "store: ${summary()}")
        if (previous != null && previous.videoId != match.videoId) {
            app?.let { fr.spotifeur.playback.AudioCache.cache(it).removeResource(fr.spotifeur.playback.YouTubeUris.cacheKey(match.trackId)) }
            mutableCorrections.tryEmit(match.trackId)
        }
        return match
    }

    private fun queryOf(track: Music) = MatchQuery(track.id, track.title, track.subtitle, track.durationMs, track.album)

    // Audio -------------------------------------------------------------------------------------------

    private val spotifySource by lazy { SpotifyHlsDataSource.Factory(checkNotNull(app), nativeAudio = BuildConfig.NATIVE_RUNTIME) }
    private val cdn by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    }
    private val leadIns = object : LinkedHashMap<String, AudioFingerprint>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioFingerprint>?) = size > 48
    }

    /** The fingerprint of a Spotify track's clear lead-in, read from the start of its file. */
    private suspend fun spotifyPrint(trackId: String, task: Task): AudioFingerprint? {
        synchronized(leadIns) { leadIns[trackId] }?.let { return it }
        val t0 = SystemClock.elapsedRealtime()
        val resolved = spotifySource.resolve(trackId) ?: return null
        val t1 = SystemClock.elapsedRealtime()
        val bytes = try { fetch(cdn, resolved.address, LEAD_IN_BYTES, SpotifyAndroid.userAgent) }
        catch (expired: HttpStatus) {
            if (expired.code !in setOf(401, 403, 410)) throw expired
            val renewed = spotifySource.renewAddress(resolved.file.hex) ?: throw expired
            fetch(cdn, renewed, LEAD_IN_BYTES, SpotifyAndroid.userAgent)
        }
        awaitDecoder(task)
        val t2 = SystemClock.elapsedRealtime()
        val decoded = AudioExcerpt.decode(bytes, LEAD_IN_MAX_MS) ?: return null
        val t3 = SystemClock.elapsedRealtime()
        val print = AudioFingerprint.of(decoded.samples, decoded.sampleRate)
        Log.d(TAG, "lead-in $trackId ${bytes.size} B → ${decoded.durationMs} ms at ${decoded.sampleRate} Hz, ${print.activeFrames}/${print.frames} active frames " +
            "(manifest ${t1 - t0}, bytes ${t2 - t1}, decode ${t3 - t2}, print ${SystemClock.elapsedRealtime() - t3} ms)")
        synchronized(leadIns) { leadIns[trackId] = print }
        return print
    }

    private suspend fun youTubePrint(videoId: String, task: Task): AudioFingerprint? {
        val t0 = SystemClock.elapsedRealtime()
        val stream = YouTubeMusic.excerptStream(videoId)
        val t1 = SystemClock.elapsedRealtime()
        val wanted = (stream.bitrate / 8 * YOUTUBE_EXCERPT_MS / 1000 + 16_384).let {
            if (stream.contentLength > 0) minOf(it, stream.contentLength) else it
        }
        val bytes = fetch(YouTubeMusic.http, stream.url, wanted, stream.userAgent)
        awaitDecoder(task)
        val t2 = SystemClock.elapsedRealtime()
        val decoded = AudioExcerpt.decode(bytes, YOUTUBE_EXCERPT_MS) ?: return null
        val t3 = SystemClock.elapsedRealtime()
        val print = AudioFingerprint.of(decoded.samples, decoded.sampleRate)
        Log.d(TAG, "excerpt $videoId ${stream.mimeType.substringBefore(';')} ${stream.bitrate / 1000} kbit/s ${bytes.size} B → ${decoded.durationMs} ms " +
            "(stream ${t1 - t0}, bytes ${t2 - t1}, decode ${t3 - t2}, print ${SystemClock.elapsedRealtime() - t3} ms)")
        return print
    }

    /**
     * The software decoder is shared by every decode in the process: background decodes wait while
     * the player waits for a match, so the track asked for gets it alone.
     */
    private suspend fun awaitDecoder(task: Task) {
        while (!task.urgent && urgent.get() > 0) delay(40)
    }

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    private fun fetch(client: OkHttpClient, url: String, length: Long, userAgent: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=0-${length - 1}").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code)
            val source = response.body?.source() ?: throw IOException("Empty response.")
            val out = java.io.ByteArrayOutputStream(length.toInt())
            val buffer = ByteArray(16 * 1024)
            val stream = source.inputStream()
            while (out.size() < length) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), length - out.size()).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try { return block() }
        finally { Log.d(TAG, "$label: ${SystemClock.elapsedRealtime() - started} ms") }
    }

    private fun sha256(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Candidates compared by ear: the first search result and the next three. */
    private const val CANDIDATES = 4
    /** New candidates heard from each wider search, and the most heard for one track before falling back. */
    private const val SECOND_SEARCH_CANDIDATES = 3
    private const val MAX_HEARD = 16
    /** Another master or re-recording of the same song, aligned with it: measured from 0.36 to 0.42. */
    private const val SAME_SONG_ERROR_RATE = 0.42
    private const val SAME_SONG_LAG_MS = 1_500L
    private const val SAME_SONG_FRAMES = 300
    private const val RECHECK_MS = 14L * 24 * 3600 * 1000
    /** When the fallback rules last changed (wider searches, missing tracks): older uncertain matches are made again. */
    private const val RULES_CHANGED_AT = 1795824000000L
    private val UNPLAYABLE_STATUSES = setOf("LOGIN_REQUIRED", "UNPLAYABLE", "ERROR", "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED",
        "CONTENT_CHECK_REQUIRED", "NO_AUDIO_FORMAT", "ALL_CLIENTS_EXCLUDED")
    /** A match this close is taken without hearing the other candidates (same master, other encoding). */
    private const val STRONG_MATCH = 0.20
    /** Tracks after the current one that are always checked by ear, whatever the network. */
    private const val QUEUE_AHEAD = 20
    private const val BACKGROUND_WORKERS = 2
    private const val BACKGROUND_GAP_MS = 250L
    private const val IDLE_WAIT_MS = 30_000L
    private const val RETRY_MS = 10 * 60_000L
    /** How long a read-ahead of the queue lets the current track's match go first. */
    private const val CURRENT_FIRST_MS = 6_000L
    /** Eight seconds at 128 kbit/s and the initialization segment; the clear lead-in lasts about ten. */
    private const val LEAD_IN_BYTES = 150_000L
    /** Decoding is the slowest step: eight seconds of lead-in are enough to tell recordings apart. */
    private const val LEAD_IN_MAX_MS = 8_000L
    /** Covers the lead-in shifted by up to about twelve seconds, which songs and most videos stay within. */
    private const val YOUTUBE_EXCERPT_MS = 15_000L
}
