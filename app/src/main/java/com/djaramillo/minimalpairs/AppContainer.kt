package com.djaramillo.minimalpairs

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.djaramillo.minimalpairs.audio.AttemptRecorder
import com.djaramillo.minimalpairs.audio.Player
import com.djaramillo.minimalpairs.audio.SentencePlayer
import com.djaramillo.minimalpairs.clips.ClipDownloader
import com.djaramillo.minimalpairs.clips.ClipPack
import com.djaramillo.minimalpairs.clips.PackRenderer
import com.djaramillo.minimalpairs.clips.PackSync
import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.storage.DataFolder
import com.djaramillo.minimalpairs.storage.Prefs
import com.djaramillo.minimalpairs.storage.SayItPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Process-wide singletons (no DI framework): preferences, the data folder,
 * the clip pack + downloader and the audio player. Created lazily from the
 * application context.
 */
class AppContainer private constructor(context: Context) {
    val app: Context = context.applicationContext
    val prefs = Prefs(app)
    val folder = DataFolder(app, prefs)
    val pack = ClipPack(app)
    val downloader = ClipDownloader(app, pack)
    val renderer = PackRenderer(pack)

    /**
     * Publishes a complete pack to the data folder as `clips.zip` and installs
     * it from there again after a reinstall, so the pack is rendered from
     * Azure once and not once per install (docs/CONTRACT.md, "`clips.zip`").
     */
    val packSync = PackSync(pack, downloader, folder, prefs)

    /**
     * Outlives any screen: the long jobs (rendering the pack, publishing it to
     * the data folder) run here, not in a ViewModel.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player = Player(app)

    /** The Say-it sentence microphone: one whole sentence per recording, straight to an `.m4a` for the coach. */
    val attemptRecorder = AttemptRecorder(app)

    /** Plays whole sentences back to back: the coach's model clip, then his own attempt. */
    val sentencePlayer = SentencePlayer(app)

    /** The unpacked `sayit.zip` (docs/CONTRACT.md); the app reads it and never writes it. */
    val sayItPack = SayItPack(app.filesDir)

    val appVersion: String by lazy {
        try {
            val pm = app.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(app.packageName, 0)
            }
            info.versionName ?: "0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0"
        }
    }

    @Volatile private var catalog: Catalog? = null

    /** `assets/catalog.json`, parsed once (~600 KB, well under a second on a phone). */
    suspend fun catalog(): Catalog {
        catalog?.let { return it }
        return withContext(Dispatchers.IO) {
            val text = app.assets.open("catalog.json").use { it.readBytes().toString(Charsets.UTF_8) }
            AppJson.json.decodeFromString(Catalog.serializer(), text).also { catalog = it }
        }
    }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
