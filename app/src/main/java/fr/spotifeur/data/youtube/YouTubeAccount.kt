package fr.spotifeur.data.youtube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Google account signed in to YouTube Music, as its account menu shows it. */
data class YouTubeAccountInfo(val name: String, val handle: String = "", val photoUrl: String? = null)

/** What the web client sends for a signed-in user: its cookies and the identities read from the page. */
internal data class YouTubeCredential(val cookie: String, val visitorData: String? = null, val dataSyncId: String? = null) {
    val sapisid: String? get() = cookieValue(cookie, "SAPISID") ?: cookieValue(cookie, "__Secure-3PAPISID")
}

/** The account's cookies were refused: it has to sign in again. */
class YouTubeAuthRequired(message: String) : java.io.IOException(message)

/**
 * The Google account used for YouTube Music playlists. The cookies come from Google's own sign-in
 * page, opened in a WebView, and are kept encrypted with a key from the Android Keystore.
 * The anonymous catalogue and audio requests never use them.
 */
object YouTubeAccount {
    private var app: Context? = null
    private val mutableInfo = MutableStateFlow<YouTubeAccountInfo?>(null)
    val info: StateFlow<YouTubeAccountInfo?> = mutableInfo.asStateFlow()
    @Volatile private var credential: YouTubeCredential? = null

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        credential = runCatching { load() }.getOrNull()
        mutableInfo.value = credential?.let { preferences?.getString(KEY_INFO, null) }?.let(::decodeInfo)
    }

    val signedIn: Boolean get() = credential != null

    internal fun require(): YouTubeCredential = credential ?: throw YouTubeAuthRequired("Connect a Google account first.")
    internal fun current(): YouTubeCredential? = credential

    /** Blocking: checks the cookies with the account menu, then keeps them. */
    fun connect(cookie: String, visitorData: String?, dataSyncId: String?): YouTubeAccountInfo {
        val full = YouTubeCredential(cookie, visitorData?.takeIf(String::isNotBlank), dataSyncId?.takeIf(String::isNotBlank))
        // The identities read from the page can be refused (HTTP 403) while the cookies alone open the
        // account: without the brand-account delegation first, then without the page's visitor identity.
        val candidates = listOf(full, full.copy(dataSyncId = null), full.copy(visitorData = null, dataSyncId = null)).distinct()
        var refused: YouTubeFailure? = null
        // A session Google has just opened can be refused for a few seconds: each form is tried twice.
        for ((attempt, value) in candidates.withIndex()) repeat(2) { round ->
            try {
                val account = YouTubeLibrary.accountInfo(value)
                if (attempt > 0 || round > 0) fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-account", "connected with fallback $attempt, try ${round + 1}")
                save(value, account)
                return account
            } catch (failure: YouTubeFailure) {
                if (failure.status != "403") throw failure
                refused = failure
                if (round == 0) Thread.sleep(CONNECT_RETRY_MS)
            }
        }
        throw refused ?: YouTubeAuthRequired("Google did not open a YouTube Music session.")
    }

    /** A delegation the service refused: requests go on as the main account. */
    internal fun dropDelegation() {
        val current = credential ?: return
        if (current.dataSyncId == null) return
        val updated = current.copy(dataSyncId = null)
        credential = updated
        runCatching { write(updated) }
        fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-account", "delegation refused, using the main account")
    }

    internal fun save(value: YouTubeCredential, account: YouTubeAccountInfo) {
        credential = value
        write(value)
        preferences?.edit { putString(KEY_INFO, encodeInfo(account)) }
        mutableInfo.value = account
    }

    /** Google rotates some cookies on its responses: the stored ones follow. */
    internal fun updateCookies(setCookies: List<String>) {
        val current = credential ?: return
        if (setCookies.isEmpty()) return
        val merged = mergeCookies(current.cookie, setCookies)
        if (merged == current.cookie) return
        val updated = current.copy(cookie = merged)
        credential = updated
        runCatching { write(updated) }
    }

    fun clear() {
        credential = null
        mutableInfo.value = null
        preferences?.edit { remove(KEY_CREDENTIAL); remove(KEY_INFO) }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private val preferences get() = app?.getSharedPreferences("youtube_account", Context.MODE_PRIVATE)

    private fun load(): YouTubeCredential? {
        val blob = preferences?.getString(KEY_CREDENTIAL, null) ?: return null
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val clear = Cipher.getInstance(CIPHER).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        }
        val json = JSONObject(clear.decodeToString())
        clear.fill(0)
        return YouTubeCredential(
            json.getString("cookie"),
            json.optString("visitorData").takeIf(String::isNotBlank),
            json.optString("dataSyncId").takeIf(String::isNotBlank),
        )
    }

    private fun write(value: YouTubeCredential) {
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
        val clear = JSONObject().put("cookie", value.cookie)
            .put("visitorData", value.visitorData.orEmpty()).put("dataSyncId", value.dataSyncId.orEmpty())
            .toString().encodeToByteArray()
        val blob = Cipher.getInstance(CIPHER).run {
            init(Cipher.ENCRYPT_MODE, key)
            check(iv.size == IV_SIZE)
            try { iv + doFinal(clear) } finally { clear.fill(0) }
        }
        preferences?.edit { putString(KEY_CREDENTIAL, Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encodeInfo(info: YouTubeAccountInfo) = JSONObject().put("name", info.name).put("handle", info.handle)
        .put("photo", info.photoUrl.orEmpty()).toString()

    private fun decodeInfo(text: String): YouTubeAccountInfo? = runCatching {
        val json = JSONObject(text)
        YouTubeAccountInfo(json.getString("name"), json.optString("handle"), json.optString("photo").takeIf(String::isNotBlank))
    }.getOrNull()

    private const val CONNECT_RETRY_MS = 2_500L
    private const val KEY_ALIAS = "spotifeur-youtube-account"
    private const val KEY_CREDENTIAL = "credential"
    private const val KEY_INFO = "info"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
}

/** The `Authorization` value of the web client: SHA-1 of "seconds SAPISID origin", in hexadecimal. */
internal fun sapisidHash(sapisid: String, origin: String, nowSeconds: Long): String {
    val digest = MessageDigest.getInstance("SHA-1").digest("$nowSeconds $sapisid $origin".toByteArray())
    return "SAPISIDHASH ${nowSeconds}_" + digest.joinToString("") { "%02x".format(it) }
}

internal fun cookieValue(cookie: String, name: String): String? = cookie.split(';')
    .map(String::trim).firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf(String::isNotEmpty)

/** `Set-Cookie` values applied to a `Cookie` header: new values replace old ones, expired cookies are removed. */
internal fun mergeCookies(cookie: String, setCookies: List<String>): String {
    val values = LinkedHashMap<String, String>()
    cookie.split(';').map(String::trim).filter { '=' in it }.forEach { values[it.substringBefore('=')] = it.substringAfter('=') }
    setCookies.forEach { header ->
        val pair = header.substringBefore(';').trim()
        if ('=' !in pair) return@forEach
        val name = pair.substringBefore('=')
        val value = pair.substringAfter('=')
        val attributes = header.split(';').drop(1).map { it.trim().lowercase() }
        val expired = value.isEmpty() || attributes.any { it == "max-age=0" || it.startsWith("max-age=-") } ||
            attributes.any { it.startsWith("expires=") && it.contains("1970") }
        if (expired) values.remove(name) else values[name] = value
    }
    return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * The page id of a brand account: `DATASYNC_ID` reads "pageId||accountId" for a brand account and
 * "accountId||" for the main one, which needs no delegation.
 */
internal fun delegatedPageId(dataSyncId: String?): String? {
    val parts = dataSyncId?.split("||") ?: return null
    return parts.getOrNull(0)?.takeIf { parts.size >= 2 && it.isNotBlank() && parts[1].isNotBlank() }
}
