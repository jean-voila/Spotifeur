package fr.spotifeur.data

import android.os.Build

/** Constants and HTTP format from the supplied Spotify Android 9.2.4.2135 APK. */
internal object SpotifyAndroid {
    const val CLIENT_ID = "9a8d2f0ce77a4e248bb71fefcb557637"
    const val VERSION = "9.2.4.2135"

    // p.j1l.h -> ApplicationScopeConfiguration.setDefaultHTTPUserAgent.
    val userAgent: String
        get() = "Spotify/$VERSION Android/${Build.VERSION.SDK_INT} (${Build.MODEL})"
}
