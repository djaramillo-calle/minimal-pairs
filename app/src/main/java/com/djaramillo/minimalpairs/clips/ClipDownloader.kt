package com.djaramillo.minimalpairs.clips

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.djaramillo.minimalpairs.domain.AppJson

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads `clips.zip` from the latest GitHub Release into `filesDir/clips/`.
 *
 * Started by an explicit tap (Settings or the Home prompt), or by [PackSync]
 * when the data folder holds a `clips.zip` and this app has no usable pack.
 * Streams to `clips.zip.part` with byte progress, unpacks into a staging directory
 * with [ZipRules] validation and a 200 MB cap, verifies `sha256.txt` when
 * present, checks the unpacked `index.json` with [DownloadCheck] (a placeholder
 * or partial pack, or one covering fewer catalog words than what is installed,
 * never replaces the current pack), then swaps the staging directory in as
 * `clips/`. Cancellable at every step; partial files are removed.
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

    /**
     * Start the download in [scope] unless one is already running.
     * [catalogVersion] and [catalogWords] (the installed catalog's trainable
     * words) are what the unpacked pack is judged against.
     */
    fun start(scope: CoroutineScope, catalogVersion: String, catalogWords: Set<String>) =
        launch(scope, catalogVersion, catalogWords) { part -> download(part) }

    /**
     * Install a `clips.zip` the user picked with the system file picker
     * (Settings → "Import clips.zip"). Same validation and swap as a download;
     * the only difference is where the bytes come from.
     */
    fun startFromUri(scope: CoroutineScope, uri: Uri, catalogVersion: String, catalogWords: Set<String>) =
        launch(scope, catalogVersion, catalogWords) { part -> copyFromUri(uri, part) }

    /**
     * Install a `clips.zip` that is already on the phone: [fill] writes the
     * archive into the file it is given, and everything after that is the
     * download's own path — unpack under [ZipRules], verify `sha256.txt`, ask
     * [DownloadCheck] whether it may replace what is installed, swap the
     * directory in. No network call is made anywhere in it.
     *
     * This is how the data folder's copy gets back in after a reinstall
     * ([PackSync]), which is the whole reason the pack is only ever rendered
     * once.
     */
    fun startFromLocalCopy(
        scope: CoroutineScope,
        catalogVersion: String,
        catalogWords: Set<String>,
        fill: suspend (File) -> Unit,
    ) = launch(scope, catalogVersion, catalogWords, fill)

    /**
     * The job that was started, or null when one was already running. A caller
     * that must know how this one ended — the automatic install from the data
     * folder — joins it and reads [state]; asking the flow for the next
     * terminal state instead would answer with a stale one from an earlier run.
     */
    private fun launch(
        scope: CoroutineScope,
        catalogVersion: String,
        catalogWords: Set<String>,
        source: suspend (File) -> Unit,
    ): Job? {
        if (job?.isActive == true) return null
        val started = scope.launch(Dispatchers.IO) {
            try {
                run(catalogVersion, catalogWords, source)
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        job = started
        return started
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    private suspend fun run(catalogVersion: String, catalogWords: Set<String>, source: suspend (File) -> Unit) {
        val part = File(app.filesDir, "clips.zip.part")
        val staging = File(app.filesDir, "clips.staging")
        try {
            _state.value = State.Downloading(0, null)
            source(part)
            staging.deleteRecursively()
            staging.mkdirs()
            _state.value = State.Unpacking(0)
            val (files, bytes) = unzip(part, staging)
            part.delete()
            _state.value = State.Verifying
            verify(staging)
            if (!File(staging, ZipRules.INDEX).isFile) throw IOException("the archive has no index.json")
            val candidate = try {
                AppJson.json.decodeFromString(ClipIndex.serializer(), File(staging, ZipRules.INDEX).readText())
            } catch (e: Exception) {
                null
            }
            val current = pack.index.value
            DownloadCheck.refuse(candidate, current.bundled, current, catalogVersion, catalogWords) { v, w ->
                File(File(staging, v), "$w.webm").let { it.isFile && it.length() > 0 }
            }?.let { throw IOException(it) }
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
        try {
            download(part, URL(RELEASE_URL))
        } catch (e: NotFound) {
            // The latest release carries no pack (a build made without Azure
            // secrets attaches none). Fall back to the newest release that has one.
            val url = newestPackUrl() ?: throw IOException(
                "no clip pack has been published on GitHub yet (the latest release has no clips.zip)",
            )
            download(part, URL(url))
        }
    }

    private class NotFound(host: String) : IOException("HTTP 404 from $host")

    /** GitHub REST listing of recent releases → the first `clips.zip` asset URL, or null. */
    private suspend fun newestPackUrl(): String? {
        val c = URL(RELEASES_API).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "minimal-pairs-android")
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return ReleaseAssets.pickClipsZip(body)
        } finally {
            c.disconnect()
        }
    }

    private suspend fun download(part: File, start: URL) {
        var url = start
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
                if (code == HttpURLConnection.HTTP_NOT_FOUND) throw NotFound(url.host)
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

    /** Copy a user-picked archive into [part], reporting progress like a download. */
    private suspend fun copyFromUri(uri: Uri, part: File) {
        val resolver = app.contentResolver
        val total: Long? = try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
            }
        } catch (e: Exception) {
            null
        }
        if (total != null && total > ZipRules.MAX_TOTAL_BYTES) {
            throw IOException("the archive is too large (${total / 1_000_000} MB)")
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("cannot open the selected file")
        part.delete()
        input.use { src ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done > ZipRules.MAX_TOTAL_BYTES) throw IOException("the archive exceeds the size cap")
                    if (done - lastReport >= 256 * 1024) {
                        lastReport = done
                        _state.value = State.Downloading(done, total)
                    }
                }
                _state.value = State.Downloading(done, total)
            }
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
        const val RELEASES_API = "https://api.github.com/repos/djaramillo-calle/minimal-pairs/releases?per_page=30"
    }
}

/** Pure helper: pick the newest release's `clips.zip` from a GitHub releases listing. */
object ReleaseAssets {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun pickClipsZip(listingJson: String): String? {
        val releases = try { lenient.parseToJsonElement(listingJson).jsonArray } catch (e: Exception) { return null }
        for (r in releases) {
            val obj = r as? kotlinx.serialization.json.JsonObject ?: continue
            if (obj["draft"]?.jsonPrimitive?.content == "true") continue
            val assets = obj["assets"]?.jsonArray ?: continue
            for (a in assets) {
                val asset = a.jsonObject
                if (asset["name"]?.jsonPrimitive?.content == "clips.zip") {
                    val url = asset["browser_download_url"]?.jsonPrimitive?.content
                    if (!url.isNullOrBlank() && url.startsWith("https://")) return url
                }
            }
        }
        return null
    }
}
