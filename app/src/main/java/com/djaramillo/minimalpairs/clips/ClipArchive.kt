package com.djaramillo.minimalpairs.clips

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Packing `filesDir/clips/` back into a `clips.zip`, and reading the
 * `index.json` out of one without unpacking it.
 *
 * The pack is rendered once (11,646 clips on the phone, ~70,000 characters of
 * neural TTS) and `filesDir/clips/` is app-private storage, which Android
 * wipes on uninstall. So a complete pack is published to the data folder as `clips.zip`
 * and installed from there again after a reinstall — the same archive layout a
 * download or a Settings import already carries (`index.json`, `sha256.txt`
 * when the pack has one, `<voice>/<word>.webm`), so the way back in is
 * [ClipDownloader]'s existing unpack-validate-swap and not a second one.
 *
 * Pure `java.io` / `java.util.zip` (no Android types) so it is unit tested on
 * the JVM. Nothing here decides *whether* to export — that is [PackExport] —
 * and nothing here touches the data folder.
 */
object ClipArchive {
    /** The pack is not all there: a clip `index.json` lists is missing or empty. */
    class Incomplete(message: String) : IOException(message)

    /** The pack does not fit inside the [ZipRules] caps the unpacker enforces. */
    class TooBig(message: String) : IOException(message)

    /** Cap on the `index.json` read out of an archive; the real one is ~120 KB. */
    const val MAX_INDEX_BYTES: Long = 8L * 1024 * 1024

    /** One file to write into the archive, under [path] (forward slashes). */
    data class Entry(val path: String, val file: File, val bytes: Long)

    data class Result(val entries: Int, val bytes: Long)

    /**
     * The clips [index] lists, as archive entries, in a stable order:
     * `sha256.txt` when the pack has one, then `<voice>/<word>.webm`
     * voice-major in index order. `index.json` is not in the list — it is
     * written from the very bytes that were parsed into [index], so the
     * archive can never carry an index that describes a different pack.
     *
     * Throws rather than quietly shipping a truncated pack: [Incomplete] when
     * a listed clip is missing or empty, [TooBig] when the entry count or the
     * byte total is beyond what [ZipRules] lets back in, and [IOException] for
     * a path [ZipRules] would refuse. A `clips.zip` that cannot be installed
     * is worth less than none: it would sit in the folder looking like the
     * pack he already paid for.
     */
    fun entries(dir: File, index: ClipIndex): List<Entry> {
        val manifest = File(dir, ZipRules.SHA256).let { f ->
            try { f.isFile && f.length() > 0 } catch (e: SecurityException) { false }
        }
        // Counted from the index before a single file is touched, so a pack
        // that could never be unpacked again fails before it is half written.
        val declared = index.voices.size.toLong() * index.words.size + 1 + (if (manifest) 1 else 0)
        if (declared > ZipRules.MAX_ENTRIES) {
            throw TooBig("the pack has $declared files, over the ${ZipRules.MAX_ENTRIES} the unpacker accepts")
        }
        val out = ArrayList<Entry>(declared.toInt())
        fun add(path: String, required: Boolean) {
            if (ZipRules.judge(path, false) !is ZipRules.Verdict.Accept) {
                throw IOException("the pack has an entry the unpacker would refuse: $path")
            }
            val f = File(dir, path)
            val bytes = try { if (f.isFile) f.length() else -1L } catch (e: SecurityException) { -1L }
            if (bytes <= 0L) {
                if (required) throw Incomplete("the pack lists $path but the file is missing or empty")
                return
            }
            if (bytes > ZipRules.MAX_ENTRY_BYTES) throw TooBig("$path is ${bytes / 1_000_000} MB, over the entry cap")
            out.add(Entry(path, f, bytes))
        }
        add(ZipRules.SHA256, required = false)
        for (voice in index.voices) {
            for (word in index.words) add("$voice/$word.webm", required = true)
        }
        val total = out.sumOf { it.bytes }
        if (total > ZipRules.MAX_TOTAL_BYTES) {
            throw TooBig("the pack is ${total / 1_000_000} MB, over the ${ZipRules.MAX_TOTAL_BYTES / 1_000_000} MB the unpacker accepts")
        }
        return out
    }

    /**
     * Write the whole pack in [dir] to [out] as a `clips.zip`.
     *
     * [indexJson] is written first and verbatim: it is the bytes [index] was
     * parsed from, so a render that rewrites `index.json` while this runs
     * cannot make the archive describe itself wrongly. [onProgress] is called
     * after every entry with how many are done, and may throw to stop the run
     * (the caller uses it to honour cancellation).
     *
     * [out] is not closed — the caller owns it — but it is fully flushed. A
     * failure part way through throws; the caller is expected to be writing to
     * a temporary name and to drop it, so a half-written archive never becomes
     * the live `clips.zip`.
     */
    fun write(
        dir: File,
        index: ClipIndex,
        indexJson: ByteArray,
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val entries = entries(dir, index)
        val total = entries.size + 1
        var written = 0
        var bytes = 0L
        val zip = ZipOutputStream(BufferedOutputStream(out))
        // index.json and sha256.txt are text and worth deflating; the clips are
        // Opus and are not, and 15,000 of them would cost minutes of CPU for
        // nothing. The entries stay DEFLATED either way, which every unpacker
        // reads, including the app's own.
        zip.setLevel(Deflater.BEST_SPEED)
        zip.putNextEntry(ZipEntry(ZipRules.INDEX))
        zip.write(indexJson)
        zip.closeEntry()
        bytes += indexJson.size
        written++
        onProgress(written, total)
        for (e in entries) {
            zip.setLevel(if (e.path.endsWith(".webm")) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(e.path))
            val copied = e.file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            if (copied != e.bytes) {
                throw Incomplete("${e.path} changed while the pack was being packed (${e.bytes} → $copied bytes)")
            }
            bytes += copied
            written++
            onProgress(written, total)
        }
        zip.finish()
        zip.flush()
        return Result(written, bytes)
    }

    /**
     * The `index.json` inside a `clips.zip`, read straight from [input] without
     * unpacking anything, or null when the archive has none (or is not a zip).
     *
     * This is the cheap question asked of the folder's copy on launch — is this
     * a pack for the catalog the app has? — before deciding to spend a 60 MB
     * copy installing it or a 60 MB write replacing it. The archives the app
     * writes put `index.json` first, so the answer usually costs one entry;
     * one written by `zip -r` may cost a scan, which is local disk and no
     * network. The scan stops at [ZipRules.MAX_TOTAL_BYTES] so a lying or
     * endless stream cannot hold the launch open.
     *
     * [input] is closed.
     */
    fun readIndexJson(input: InputStream, maxScanBytes: Long = ZipRules.MAX_TOTAL_BYTES): String? = try {
        ZipInputStream(input).use { zin ->
            var scanned = 0L
            var found: String? = null
            while (found == null && scanned < maxScanBytes) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory || ZipRules.normalise(entry.name) != ZipRules.INDEX) {
                    scanned += skip(zin, maxScanBytes - scanned)
                    zin.closeEntry()
                    continue
                }
                found = readEntry(zin)
                zin.closeEntry()
            }
            found
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        // A corrupt local header; not a pack anything here can read.
        null
    }

    /** The current entry as UTF-8 text, or null when it is bigger than an index ever is. */
    private fun readEntry(input: InputStream): String? {
        val buf = ByteArray(64 * 1024)
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (bytes.size() + n > MAX_INDEX_BYTES) return null
            bytes.write(buf, 0, n)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** Read and discard at most [limit] bytes of the current entry; returns how many. */
    private fun skip(input: InputStream, limit: Long): Long {
        if (limit <= 0) return 0
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (done < limit) {
            val n = input.read(buf)
            if (n < 0) break
            done += n
        }
        return done
    }
}
