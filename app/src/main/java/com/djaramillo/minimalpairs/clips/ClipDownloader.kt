package com.djaramillo.minimalpairs.clips

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads `clips.zip` from the latest GitHub Release into `filesDir/clips/`.
 *
 * Only ever started by an explicit tap (Settings or the Home prompt). Streams
 * to `clips.zip.part` with byte progress, unpacks into a staging directory
 * with [ZipRules] validation and a 200 MB cap, verifies `sha256.txt` when
 * present, then swaps the staging directory in as `clips/`. Cancellable at
 * every step; partial files are removed.
 */
class ClipDownloader(context: Context, private val pack: ClipPack) {
    private val app = context.applicationContext

    sealed class State {
        data object Idle : State()
        /** [total] is null when the server sent no Content-Length. */
        data class Downloading(val bytes: Long, val total: Long?) : State()
        data class Unpacking(val files: Int) : State()
        data object Verifying : State()
        data class Done(val files: Int, val bytes: Long) : State()
        data class Failed(val message: String) : State()

        val isRunning: Boolean get() = this is Downloading || this is Unpacking || this is Verifying
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** Start the download in [scope] unless one is already running. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                run()
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    private suspend fun run() {
        val part = File(app.filesDir, "clips.zip.part")
        val staging = File(app.filesDir, "clips.staging")
        try {
            _state.value = State.Downloading(0, null)
            download(part)
            staging.deleteRecursively()
            staging.mkdirs()
            _state.value = State.Unpacking(0)
            val (files, bytes) = unzip(part, staging)
            part.delete()
            _state.value = State.Verifying
            verify(staging)
            if (!File(staging, ZipRules.INDEX).isFile) throw IOException("the archive has no index.json")
            // Swap in.
            val target = pack.downloadedDir
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            pack.reload()
            _state.value = State.Done(files, bytes)
        } finally {
            part.delete()
            staging.deleteRecursively()
        }
    }

    private suspend fun download(part: File) {
        var url = URL(RELEASE_URL)
        var conn: HttpURLConnection? = null
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Accept", "application/octet-stream")
            c.setRequestProperty("User-Agent", "minimal-pairs-android")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                c.disconnect()
                if (++redirects > 8) throw IOException("too many redirects")
                url = URL(url, loc)
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                c.disconnect()
                throw IOException("HTTP $code from ${url.host}")
            }
            conn = c
            break
        }
        val c = conn!!
        val total = c.contentLengthLong.takeIf { it > 0 }
        if (total != null && total > ZipRules.MAX_TOTAL_BYTES) {
            c.disconnect(); throw IOException("clips.zip is too large (${total / 1_000_000} MB)")
        }
        try {
            part.delete()
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > ZipRules.MAX_TOTAL_BYTES) throw IOException("clips.zip exceeds the size cap")
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            _state.value = State.Downloading(done, total)
                        }
                    }
                    _state.value = State.Downloading(done, total)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /** Returns (files written, bytes written). */
    private suspend fun unzip(zip: File, into: File): Pair<Int, Long> {
        var files = 0
        var total = 0L
        val buf = ByteArray(64 * 1024)
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zin ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zin.nextEntry ?: break
                when (val v = ZipRules.judge(entry.name, entry.isDirectory)) {
                    is ZipRules.Verdict.Skip -> { zin.closeEntry(); continue }
                    is ZipRules.Verdict.Reject -> throw IOException("refused archive entry: ${v.reason}")
                    is ZipRules.Verdict.Accept -> {
                        if (entry.size > ZipRules.MAX_ENTRY_BYTES) throw IOException("entry too large: ${entry.name}")
                        if (++files > ZipRules.MAX_ENTRIES) throw IOException("too many files in the archive")
                        val out = File(into, v.relativePath)
                        val canonicalParent = into.canonicalPath
                        if (!out.canonicalPath.startsWith(canonicalParent + File.separator)) {
                            throw IOException("entry escapes the target: ${entry.name}")
                        }
                        out.parentFile?.mkdirs()
                        var entryBytes = 0L
                        out.outputStream().use { o ->
                            while (true) {
                                val n = zin.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                entryBytes += n
                                total += n
                                if (entryBytes > ZipRules.MAX_ENTRY_BYTES) throw IOException("entry too large: ${entry.name}")
                                if (total > ZipRules.MAX_TOTAL_BYTES) throw IOException("archive exceeds the size cap")
                            }
                        }
                        zin.closeEntry()
                        if (files % 50 == 0) _state.value = State.Unpacking(files)
                    }
                }
            }
        }
        _state.value = State.Unpacking(files)
        return files to total
    }

    /** Verify every `sha256.txt` entry that exists; a mismatch or a listed-but-missing file fails. */
    private suspend fun verify(dir: File) {
        val manifest = File(dir, ZipRules.SHA256)
        if (!manifest.isFile) return
        val expected = ZipRules.parseSha256(manifest.readText())
        for ((path, hex) in expected) {
            currentCoroutineContext().ensureActive()
            val f = File(dir, path)
            if (!f.isFile) throw IOException("sha256.txt lists a missing file: $path")
            val actual = sha256(f.inputStream())
            if (actual != hex) throw IOException("checksum mismatch: $path")
        }
    }

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        input.use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun deleteDownloaded() = withContext(Dispatchers.IO) {
        if (job?.isActive == true) return@withContext
        pack.deleteDownloaded()
        _state.value = State.Idle
    }

    companion object {
        const val RELEASE_URL = "https://github.com/djaramillo-calle/minimal-pairs/releases/latest/download/clips.zip"
    }
}
