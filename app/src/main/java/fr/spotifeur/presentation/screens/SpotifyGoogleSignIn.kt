package fr.spotifeur.presentation.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import fr.spotifeur.R

/** Where Google sends the result back for Spotify's client. Login5 needs this exact address with a code. */
internal const val SPOTIFY_GOOGLE_REDIRECT = "https://accounts.spotify.com/login/google/redirect"
/**
 * Spotify's Google client, the one both `accounts.spotify.com/login/google` and the 9.2 app
 * (`serverClientId` in `classes4.dex`) use.
 */
private const val SPOTIFY_GOOGLE_CLIENT = "1046568431490-ij1gi5shcp2gtorls09frkc56d4mjbe2.apps.googleusercontent.com"

/**
 * Google's authorization page for Spotify's client, asking for a code and an ID token at once:
 * the 9.2 app signs in with either.
 */
internal fun spotifyGoogleUrl(state: String, nonce: String): String = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
    .appendQueryParameter("response_type", "code id_token")
    .appendQueryParameter("client_id", SPOTIFY_GOOGLE_CLIENT)
    .appendQueryParameter("scope", "openid email profile")
    .appendQueryParameter("redirect_uri", SPOTIFY_GOOGLE_REDIRECT)
    .appendQueryParameter("state", state)
    .appendQueryParameter("nonce", nonce)
    .appendQueryParameter("prompt", "select_account")
    .build().toString()

/**
 * The code Google returns to Spotify's sign-in page, or the error it reports instead. Null for any
 * other address.
 */
internal sealed interface GoogleRedirect {
    data class Signed(val code: String?, val idToken: String?, val state: String?) : GoogleRedirect
    data class Refused(val error: String) : GoogleRedirect
}

/** A hybrid response comes in the fragment, a code-only one in the query: both are read. */
internal fun googleRedirect(url: String?): GoogleRedirect? {
    val uri = url?.let(Uri::parse) ?: return null
    if (uri.scheme != "https" || uri.host != "accounts.spotify.com" || uri.path?.trimEnd('/') != "/login/google/redirect") return null
    val fragment = uri.encodedFragment?.let { Uri.parse("https://x/?$it") }
    fun value(name: String) = (fragment?.getQueryParameter(name) ?: uri.getQueryParameter(name))?.takeIf(String::isNotBlank)
    val code = value("code")
    val idToken = value("id_token")
    if (code != null || idToken != null) return GoogleRedirect.Signed(code, idToken, value("state"))
    return GoogleRedirect.Refused(value("error") ?: "no_code")
}

/**
 * Google's sign-in in a WebView, for Spotify's own Google client. The redirect back to Spotify is
 * stopped before it loads: its ID token and code go to Login5, as the official app sends them.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpotifyGoogleSignIn(onSigned: (code: String?, idToken: String?) -> Unit, onFailed: () -> Unit, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var done by remember { mutableStateOf(false) }
    val state = remember { java.util.UUID.randomUUID().toString() }
    val nonce = remember { java.util.UUID.randomUUID().toString() }
    fun finish(result: GoogleRedirect) {
        if (done) return
        done = true
        // Nothing of this sign-in stays in the WebView.
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        when (result) {
            is GoogleRedirect.Signed -> if (result.state == state) onSigned(result.code, result.idToken) else {
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("login", "google redirect with another state")
                onFailed()
            }
            is GoogleRedirect.Refused -> {
                fr.spotifeur.data.diagnostics.DiagnosticLog.record("login", "google redirect error=${result.error.take(40)}")
                if (result.error == "access_denied") onClose() else onFailed()
            }
        }
    }
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spotify_google_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_cancel)) }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuses its sign-in to pages that announce an embedded WebView.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                            .replace(Regex("""Version/\S+\s"""), "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val redirect = googleRedirect(request.url.toString()) ?: return false
                                finish(redirect)
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                googleRedirect(url)?.let { view.stopLoading(); finish(it) }
                            }
                        }
                        CookieManager.getInstance().removeAllCookies { loadUrl(spotifyGoogleUrl(state, nonce)) }
                        webView = this
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
    }
}
