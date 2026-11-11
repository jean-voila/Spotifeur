package fr.spotifeur

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import fr.spotifeur.data.NetworkMonitor
import java.io.File

class SpotifeurApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        fr.spotifeur.data.diagnostics.DiagnosticLog.init(this)
        fr.spotifeur.data.diagnostics.DiagnosticLog.catchCrashes()
        fr.spotifeur.data.diagnostics.DiagnosticLog.recordPastExits(this)
        NetworkMonitor.start(this)
        fr.spotifeur.data.youtube.YouTubeMusic.init(this)
        fr.spotifeur.data.youtube.TrackMatcher.init(this)
        fr.spotifeur.data.youtube.YouTubeAccount.init(this)
    }

    /** Covers stay on disk whatever the CDN's cache headers say: they never change for a given address. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache {
            DiskCache.Builder().directory(File(noBackupFilesDir, "image-cache")).maxSizeBytes(IMAGE_CACHE_BYTES).build()
        }
        .respectCacheHeaders(false)
        // Without network a cover comes from disk or not at all, at once rather than after a timeout.
        .okHttpClient {
            okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                if (!NetworkMonitor.online.value) throw java.io.IOException("No connection")
                chain.proceed(chain.request())
            }.build()
        }
        .build()

    private companion object { const val IMAGE_CACHE_BYTES = 1024L * 1024 * 1024 }
}
