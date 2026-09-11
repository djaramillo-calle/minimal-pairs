package com.djaramillo.minimalpairs.clips

import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.domain.model.Catalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Renders the clip pack on the phone with the user's own Azure Speech key,
 * into the same directory a downloaded pack uses (`filesDir/clips/`), so
 * everything else (merge with the bundled subset, delete, coverage) is
 * unchanged. Resumable: clips already on disk are skipped, so a cancelled
 * or interrupted run continues where it stopped. Word-major in
 * [RenderPlan.renderOrder] order, so the heaviest contrasts become drillable
 * first; `index.json` is rewritten every few words and the pack reloaded, so
 * the Home screen sees progress while it runs.
 */
class PackRenderer(private val pack: ClipPack) {

    sealed class State {
        data object Idle : State()
        data class Running(
            val wordsDone: Int, val wordsTotal: Int,
            val clipsDone: Int, val clipsTotal: Int,
            val currentWord: String, val failures: Int,
        ) : State()
        data class Done(val words: Int, val clips: Int, val failures: Int, val complete: Boolean) : State()
        data class Failed(val message: String) : State()

        val isRunning: Boolean get() = this is Running
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun start(scope: CoroutineScope, catalog: Catalog, region: String, key: String) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                run(catalog, AzureTts(region, key))
            } catch (e: CancellationException) {
                // Leave what was rendered; the index written so far stays valid.
                try { writeIndex(catalog, RenderPlan.voicesFor(catalog)) } catch (_: IOException) {}
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun reset() { if (job?.isActive != true) _state.value = State.Idle }

    private suspend fun run(catalog: Catalog, tts: AzureTts) {
        val voices = RenderPlan.voicesFor(catalog)
        val words = RenderPlan.renderOrder(catalog)
        if (words.isEmpty() || voices.isEmpty()) throw IOException("nothing to render")
        val dir = pack.downloadedDir
        dir.mkdirs()
        val clipsTotal = words.size * voices.size
        var clipsDone = 0
        var failures = 0
        var wordsDone = 0
        val gate = Semaphore(PARALLEL)
        _state.value = State.Running(0, words.size, 0, clipsTotal, words.first(), 0)
        var sinceIndex = 0
        for (word in words) {
            currentCoroutineContext().ensureActive()
            val missing = voices.filter { v -> !clipFile(dir, v, word).let { it.isFile && it.length() > 0 } }
            clipsDone += voices.size - missing.size
            if (missing.isNotEmpty()) {
                val results = coroutineScope {
                    missing.map { v ->
                        async {
                            gate.withPermit {
                                try {
                                    val data = tts.synthesize(v, word)
                                    writeAtomic(clipFile(dir, v, word), data)
                                    true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: IOException) {
                                    // A key or region problem stops the run; anything else skips the clip.
                                    if (e.message?.startsWith("Azure rejected") == true ||
                                        e.message?.startsWith("Azure refused") == true ||
                                        e.message?.startsWith("Azure endpoint") == true
                                    ) throw e
                                    false
                                }
                            }
                        }
                    }.awaitAll()
                }
                clipsDone += results.count { it }
                failures += results.count { !it }
            }
            wordsDone++
            _state.value = State.Running(wordsDone, words.size, clipsDone, clipsTotal, word, failures)
            if (++sinceIndex >= INDEX_EVERY_WORDS) {
                sinceIndex = 0
                writeIndex(catalog, voices)
                pack.reload()
            }
        }
        val index = writeIndex(catalog, voices)
        pack.reload()
        _state.value = State.Done(index.words.size, index.files, failures, index.complete)
    }

    private fun clipFile(dir: File, voice: String, word: String) = File(File(dir, voice), "$word.webm")

    private fun writeAtomic(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("cannot write ${target.name}") }
    }

    /** Rebuild `index.json` from the files on disk (tmp + rename). */
    private fun writeIndex(catalog: Catalog, voices: List<String>): ClipIndex {
        val dir = pack.downloadedDir
        val index = RenderPlan.buildIndex(catalog, voices) { v, w ->
            clipFile(dir, v, w).takeIf { it.isFile }?.length()
        }
        val text = AppJson.json.encodeToString(ClipIndex.serializer(), index)
        val tmp = File(dir, ZipRules.INDEX + ".tmp")
        tmp.writeText(text)
        val target = File(dir, ZipRules.INDEX)
        if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
        // A stale manifest from an earlier download would fail verification of a later import.
        File(dir, ZipRules.SHA256).delete()
        return index
    }

    companion object {
        /** Concurrent requests; Azure allows far more, the phone's radio is the limit. */
        const val PARALLEL = 4
        const val INDEX_EVERY_WORDS = 25
    }
}
