package com.djaramillo.minimalpairs.clips

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.djaramillo.minimalpairs.domain.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Where the clips are: bundled in the APK (`assets/clips/`) and/or downloaded
 * into `filesDir/clips/`. Both hold `index.json` + `<voice>/<word>.webm`.
 *
 * [index] is the merged view (a word/voice is available if either source has
 * it); [open] hands the decoder an uncompressed asset file descriptor (the
 * build marks `.webm` as `noCompress`) or a plain file.
 */
class ClipPack(context: Context) {
    private val app = context.applicationContext
    private val assets = app.assets

    /** Downloaded pack root; `index.json` inside marks it usable. */
    val downloadedDir: File = File(app.filesDir, "clips")

    private val _index = MutableStateFlow(MergedIndex(null, null))
    val index: StateFlow<MergedIndex> = _index

    /** One clip ready to be decoded. Close it after decoding. */
    sealed class Source : AutoCloseable {
        class Asset(val afd: AssetFileDescriptor) : Source() {
            override fun close() = afd.close()
        }
        class Plain(val file: File) : Source() {
            override fun close() {}
        }
    }

    /** Re-read both `index.json` files. Call on launch and after a download / delete. */
    suspend fun reload(): MergedIndex = withContext(Dispatchers.IO) {
        val bundled = readAssetIndex()
        val downloaded = readDownloadedIndex()
        MergedIndex(bundled, downloaded).also { _index.value = it }
    }

    private fun readAssetIndex(): ClipIndex? = try {
        assets.open("$ASSET_ROOT/${ZipRules.INDEX}").use { parseIndex(it.readBytes()) }
    } catch (e: IOException) {
        null
    }

    private fun readDownloadedIndex(): ClipIndex? {
        val f = File(downloadedDir, ZipRules.INDEX)
        if (!f.isFile) return null
        return try { parseIndex(f.readBytes()) } catch (e: IOException) { null }
    }

    private fun parseIndex(bytes: ByteArray): ClipIndex? = try {
        AppJson.json.decodeFromString(ClipIndex.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    /**
     * Open the clip for [word] in [voice]. Tries the source the index names
     * first, then the other one, so a stale index does not stop playback.
     * @throws FileNotFoundException when no source has the file.
     */
    fun open(word: String, voice: String): Source {
        val merged = _index.value
        val order = when (merged.sourceFor(word, voice)) {
            ClipSourceKind.DOWNLOADED -> listOf(ClipSourceKind.DOWNLOADED, ClipSourceKind.BUNDLED)
            else -> listOf(ClipSourceKind.BUNDLED, ClipSourceKind.DOWNLOADED)
        }
        for (kind in order) {
            val s = when (kind) {
                ClipSourceKind.BUNDLED -> openAsset(voice, word)
                ClipSourceKind.DOWNLOADED -> openDownloaded(voice, word)
            }
            if (s != null) return s
        }
        throw FileNotFoundException("no clip for '$word' in $voice")
    }

    private fun openAsset(voice: String, word: String): Source? {
        val path = "$ASSET_ROOT/$voice/$word.webm"
        return try {
            Source.Asset(assets.openFd(path))
        } catch (e: FileNotFoundException) {
            // Either absent or (unexpectedly) compressed. Distinguish by opening as a stream:
            // a compressed asset is copied out to the cache once and read from there.
            try {
                assets.open(path).use { input ->
                    val dir = File(app.cacheDir, "asset-clips/$voice").apply { mkdirs() }
                    val out = File(dir, "$word.webm")
                    if (!out.isFile) {
                        val tmp = File(dir, "$word.webm.part")
                        tmp.outputStream().use { input.copyTo(it) }
                        if (!tmp.renameTo(out)) return null
                    }
                    Source.Plain(out)
                }
            } catch (e2: IOException) {
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun openDownloaded(voice: String, word: String): Source? {
        val f = File(File(downloadedDir, voice), "$word.webm")
        return if (f.isFile && f.length() > 0) Source.Plain(f) else null
    }

    /** Remove the downloaded pack. */
    suspend fun deleteDownloaded(): MergedIndex = withContext(Dispatchers.IO) {
        downloadedDir.deleteRecursively()
        reload()
    }

    /** Bytes used by the downloaded pack (0 when none). */
    suspend fun downloadedBytes(): Long = withContext(Dispatchers.IO) {
        if (!downloadedDir.isDirectory) 0L
        else downloadedDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        const val ASSET_ROOT = "clips"
    }
}
