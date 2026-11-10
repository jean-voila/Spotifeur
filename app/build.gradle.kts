import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val nativeRuntime = providers.gradleProperty("nativeRuntime").map(String::toBoolean).getOrElse(true)

android {
    namespace = "fr.spotifeur"
    compileSdk = 37

    defaultConfig {
        // A separate installation keeps the regular account and downloads intact during device tests.
        applicationId = providers.gradleProperty("spotifeurTestApplicationId").getOrElse("fr.spotifeur")
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "NATIVE_RUNTIME", nativeRuntime.toString())
        buildConfigField("boolean", "FORCE_OFFLINE", project.hasProperty("spotifeurNoInternet").toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (nativeRuntime) {
        // This supplied JNI version uses dotted FindClass names rejected by CheckJNI.
        // Run it with the same non-debuggable configuration as the source application.
        buildTypes.getByName("debug").isDebuggable = false
        packaging.jniLibs.useLegacyPackaging = true
        sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/spotifyRuntime/assets").get().asFile)
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/spotifyRuntime/jniLibs").get().asFile)
        // Offline verification: no socket can open, with no phone setting touched.
        if (project.hasProperty("spotifeurNoInternet")) sourceSets.getByName("debug").manifest.srcFile("src/noInternet/AndroidManifest.xml")
    }
}

if (nativeRuntime) {
    val bootstrap = tasks.register<Exec>("prepareNativeBootstrap") {
        dependsOn("prepareSpotifyRuntime")
        inputs.dir(rootProject.file("native-runtime/bootstrap"))
        inputs.dir(rootProject.file("native-runtime/compile-stubs"))
        inputs.file(rootProject.file("native-runtime/build-bootstrap.sh"))
        outputs.file(layout.buildDirectory.file("generated/spotifyRuntime/assets/spotify-runtime/bootstrap.dex"))
        commandLine("bash", rootProject.file("native-runtime/build-bootstrap.sh").absolutePath)
    }
    tasks.named("preBuild").configure { dependsOn(bootstrap) }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.google.protobuf:protobuf-javalite:4.32.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // UI building blocks.
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.10.6")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("ir.mahozad.multiplatform:wavy-slider:2.2.0")
    implementation("com.github.racra:smooth-corner-rect-android-compose:v1.0.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.media3:media3-exoplayer-dash:1.11.1")
}


// The runtime comes only from the APKM supplied by the user. Never download or
// check the binary into Git. Keep its class loader separate from the app's.
// Pass -PspotifyArtifact=/path/to/spotify.apkm, or place the extracted files in native-runtime/artifacts.
val spotifyArtifact = providers.gradleProperty("spotifyArtifact").map { rootProject.file(it) }
    .orElse(rootProject.file("native-runtime/artifacts/spotify-9.2.0.26.apkm"))
val runtimeAssets = layout.buildDirectory.dir("generated/spotifyRuntime/assets")
val runtimeLibraries = layout.buildDirectory.dir("generated/spotifyRuntime/jniLibs")
val spotifyBaseArtifact = providers.gradleProperty("spotifyBaseArtifact").map { rootProject.file(it) }
    .orElse(rootProject.file("native-runtime/artifacts/spotify-9.2.0.26.base.apk")).filter { it.isFile }
val spotifyOrbitArtifact = providers.gradleProperty("spotifyOrbitArtifact").map { rootProject.file(it) }
    .orElse(rootProject.file("native-runtime/artifacts/spotify-9.2.0.26.orbit.so")).filter { it.isFile }
val useExtractedArtifacts = !providers.gradleProperty("spotifyArtifact").isPresent && spotifyBaseArtifact.isPresent && spotifyOrbitArtifact.isPresent

tasks.register("prepareSpotifyRuntime") {
    group = "build"
    description = "Extract the pinned native engine from the locally supplied Spotify APKM."
    if (useExtractedArtifacts) {
        inputs.file(spotifyBaseArtifact)
        inputs.file(spotifyOrbitArtifact)
    } else inputs.file(spotifyArtifact)
    outputs.dir(runtimeAssets)
    outputs.dir(runtimeLibraries)
    doLast {
        val assets = runtimeAssets.get().asFile.resolve("spotify-runtime").apply { mkdirs() }
        val libs = runtimeLibraries.get().asFile.resolve("arm64-v8a").apply { mkdirs() }
        fun copyChecked(input: java.io.InputStream, target: File, expected: String) {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(input, digest).use { source -> target.outputStream().use { source.copyTo(it) } }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected) {
                target.delete()
                "The supplied artifact differs from Spotify Android 9.2.0.26 (148373686)."
            }
        }
        val base = temporaryDir.resolve("base.apk")
        fun extractBase(input: java.io.InputStream) {
            copyChecked(input, base,
                "cc165669d31851b1193cae7d54c9261579c431b432080970f460704d06e4612f")
            ZipFile(base).use { apk ->
                ZipOutputStream(assets.resolve("engine.jar").outputStream().buffered()).use { archive ->
                    apk.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                        .sortedBy { it.name }.forEach { entry ->
                            archive.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                            apk.getInputStream(entry).use { it.copyTo(archive) }
                            archive.closeEntry()
                        }
                }
            }
        }
        if (useExtractedArtifacts) {
            extractBase(spotifyBaseArtifact.get().inputStream())
            copyChecked(spotifyOrbitArtifact.get().inputStream(), libs.resolve("liborbit-jni-spotify.so"),
                "2353f26f5e174917b36c3c9de4538717c6348cc0bacf8b2c094f22d1447538e2")
        } else ZipFile(spotifyArtifact.get()).use { apkm ->
            extractBase(apkm.getInputStream(apkm.getEntry("base.apk") ?: error("Missing base.apk")))
            val split = temporaryDir.resolve("arm64.apk")
            apkm.getInputStream(apkm.getEntry("split_config.arm64_v8a.apk") ?: error("Missing ARM64 split"))
                .use { source -> split.outputStream().use { source.copyTo(it) } }
            ZipFile(split).use { apk ->
                val library = "liborbit-jni-spotify.so"
                copyChecked(apk.getInputStream(apk.getEntry("lib/arm64-v8a/$library") ?: error("Missing Orbit library")),
                    libs.resolve(library), "2353f26f5e174917b36c3c9de4538717c6348cc0bacf8b2c094f22d1447538e2")
            }
        }
    }
}
