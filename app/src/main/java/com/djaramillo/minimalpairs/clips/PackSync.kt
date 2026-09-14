package com.djaramillo.minimalpairs.clips

import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.storage.DataFolder
import com.djaramillo.minimalpairs.storage.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps the rendered clip pack and the data folder's `clips.zip` in step, so
 * the pack is rendered from Azure **once**.
 *
 * `filesDir/clips/` is app-private storage and Android wipes it on uninstall.
 * Rendering the pack again is 11,646 clips on the phone (15,126 in the pack the
 * release carries, which covers the production-only words too), roughly 70,000
 * characters of neural TTS — a sixth of the free tier's month and half an hour
 * of the learner's evening — and it had already been paid for twice before
 * this existed. So:
 *
 * - a complete pack is published to the folder as `clips.zip` (once per
 *   catalog version, in the background, without asking), and
 * - an app with no usable pack that finds a `clips.zip` there installs it
 *   instead of calling Azure.
 *
 * It never renders. Rendering stays a deliberate Settings action, because it
 * is the expensive thing this class exists to avoid.
 *
 * Nothing here validates or unpacks an archive itself: installing goes through
 * [ClipDownloader], which already streams, unpacks under [ZipRules], asks
 * [DownloadCheck] whether the result may replace what is installed, and swaps
 * the directory in atomically. [PackExport] makes the decision, [ClipArchive]
 * does the packing, [DataFolder] owns the writing.
 */
class PackSync(
    private val pack: ClipPack,
    private val downloader: ClipDownloader,
    private val folder: DataFolder,
    private val prefs: Prefs,
) {
    sealed class State {
        data object Idle : State()
        /** Packing `filesDir/clips/` into the folder's `clips.zip`. */
        data class Exporting(val done: Int, val total: Int) : State()
        /** Installing the folder's `clips.zip` (the work itself is [ClipDownloader]'s). */
        data object Importing : State()
        data class Exported(val entries: Int, val bytes: Long) : State()
        /** Nothing needed doing; [reason] is why, for Settings. */
        data class Skipped(val reason: String) : State()
        data class Failed(val message: String) : State()

        val isRunning: Boolean get() = this is Exporting || this is Importing
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null

    /**
     * One automatic install attempt per process. Without it a folder copy that
     * fails validation would be copied and unpacked again at every launch and
     * after every return to the foreground — tens of megabytes each time, for
     * an archive that has already been refused. Settings → "Import clips.zip…"
     * is still there for a deliberate retry.
     */
    private var importAttempted = false

    /**
     * Decide and act, unless something is already doing it. Returns at once:
     * the work runs in [scope], which must outlive the screen — the export is
     * tens of megabytes and the learner is expected to walk away from it.
     *
     * Safe to call on every launch, on every return to the foreground and
     * after the folder is picked: with the pack published and the zip in place
     * it costs one directory listing and nothing else.
     */
    @Synchronized
    fun run(scope: CoroutineScope, catalogVersion: String, catalogWords: Set<String>) {
        if (job?.isActive == true) return
        if (downloader.state.value.isRunning) return
        job = scope.launch(Dispatchers.IO) {
            try {
                step(this, catalogVersion, catalogWords)
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun step(scope: CoroutineScope, catalogVersion: String, catalogWords: Set<String>) {
        val merged = pack.index.value
        val hasZip = folder.hasClipsZip()
        val action = PackExport.decide(
            downloaded = merged.downloaded,
            catalogVersion = catalogVersion,
            catalogWords = catalogWords,
            folderHasZip = hasZip,
            exportedVersion = prefs.exportedPackCatalog,
        )
        when (action) {
            PackExport.Action.NOTHING -> _state.value = State.Skipped(
                PackExport.refuseExport(merged.downloaded, catalogWords) ?: "the folder already has this pack",
            )
            PackExport.Action.IMPORT -> install(scope, catalogVersion, catalogWords)
            PackExport.Action.EXPORT -> export(catalogVersion, catalogWords, folderHasZip = hasZip)
        }
    }

    /**
     * Install the folder's `clips.zip` instead of rendering.
     *
     * The archive's own `index.json` is read first — cheap, nothing is
     * unpacked — and judged by the rule a download is judged by, so a pack
     * rendered for another catalog is refused here rather than after a 60 MB
     * copy. Everything after that is [ClipDownloader]'s existing path, and
     * there is no Azure call anywhere in it.
     */
    private suspend fun install(scope: CoroutineScope, catalogVersion: String, catalogWords: Set<String>) {
        if (importAttempted) {
            _state.value = State.Skipped("the folder's clips.zip has already been tried this run")
            return
        }
        importAttempted = true
        val current = pack.index.value
        val refused = PackExport.refuseFolderZip(
            folderIndex(), current.bundled, current, catalogVersion, catalogWords,
        )
        if (refused != null) {
            _state.value = State.Failed(refused)
            return
        }
        _state.value = State.Importing
        val install = downloader.startFromLocalCopy(scope, catalogVersion, catalogWords) { part ->
            if (!folder.copyClipsZip(part, ZipRules.MAX_TOTAL_BYTES)) {
                throw IOException("the folder's clips.zip could not be read")
            }
        }
        if (install == null) {
            _state.value = State.Skipped("a pack is already being installed")
            return
        }
        // Wait for that one job, so this never sits in "installing" for ever,
        // and so a pack that came *from* the folder is not written straight
        // back to it.
        install.join()
        if (downloader.state.value is ClipDownloader.State.Done) {
            prefs.exportedPackCatalog = catalogVersion
        }
        _state.value = State.Idle
    }

    /**
     * Publish the pack as `clips.zip`.
     *
     * When the folder already holds an archive that would install on this
     * catalog, nothing is written: the marker is set and that is the end of
     * it. Rewriting the same 60 MB would cost the learner's upload allowance
     * for nothing — the folder is mirrored to Drive by his Autosync.
     */
    private suspend fun export(catalogVersion: String, catalogWords: Set<String>, folderHasZip: Boolean) {
        val merged = pack.index.value
        val dir = pack.downloadedDir
        val indexJson = try {
            File(dir, ZipRules.INDEX).readBytes()
        } catch (e: IOException) {
            _state.value = State.Failed("the pack's index.json could not be read")
            return
        }
        val index = parse(indexJson.toString(Charsets.UTF_8))
        if (index == null || PackExport.refuseExport(index, catalogWords) != null) {
            // The pack changed between the decision and now (a render finishing,
            // a delete): leave it alone, the next launch decides again.
            _state.value = State.Skipped("the pack is no longer complete")
            return
        }
        if (folderHasZip) {
            val existing = folderIndex()
            if (PackExport.refuseFolderZip(existing, merged.bundled, merged, catalogVersion, catalogWords) == null) {
                prefs.exportedPackCatalog = catalogVersion
                _state.value = State.Skipped("the folder already has this pack")
                return
            }
        }
        _state.value = State.Exporting(0, index.voices.size * index.words.size + 1)
        val self = currentCoroutineContext()[Job]
        var result: ClipArchive.Result? = null
        val outcome = folder.writeClipsZip { out ->
            result = ClipArchive.write(dir, index, indexJson, out) { done, total ->
                if (self?.isActive == false) throw CancellationException("the export was cancelled")
                if (done % PROGRESS_EVERY == 0 || done == total) _state.value = State.Exporting(done, total)
            }
        }
        currentCoroutineContext().ensureActive()
        val written = result
        _state.value = when {
            outcome == DataFolder.WriteOutcome.WRITTEN && written != null -> {
                prefs.exportedPackCatalog = catalogVersion
                State.Exported(written.entries, written.bytes)
            }
            outcome == DataFolder.WriteOutcome.FOLDER_UNAVAILABLE -> State.Skipped("the data folder is not available")
            else -> State.Failed("clips.zip could not be written to the data folder")
        }
    }

    /** The `index.json` of the folder's `clips.zip`, without unpacking it. */
    private suspend fun folderIndex(): ClipIndex? =
        folder.readClipsZip { ClipArchive.readIndexJson(it) }?.let { parse(it) }

    private fun parse(text: String): ClipIndex? = try {
        AppJson.json.decodeFromString(ClipIndex.serializer(), text)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Report progress every N entries: 15,000 state writes would be the expensive part. */
        const val PROGRESS_EVERY = 250
    }
}
