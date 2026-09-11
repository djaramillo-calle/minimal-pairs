package com.djaramillo.minimalpairs

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.djaramillo.minimalpairs.audio.Player
import com.djaramillo.minimalpairs.audio.Recorder
import com.djaramillo.minimalpairs.clips.ClipDownloader
import com.djaramillo.minimalpairs.clips.ClipPack
import com.djaramillo.minimalpairs.clips.PackRenderer
import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.storage.DataFolder
import com.djaramillo.minimalpairs.storage.Prefs
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
    /** Outlives any screen: long jobs (rendering the pack) run here, not in a ViewModel. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player = Player(app)
    /** The Say-it microphone (one `AudioRecord` per recording; holds nothing between recordings). */
    val recorder = Recorder(app)

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
