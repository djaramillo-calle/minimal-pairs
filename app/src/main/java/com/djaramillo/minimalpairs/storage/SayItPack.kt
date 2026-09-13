package com.djaramillo.minimalpairs.storage

import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.domain.SayItZip
import com.djaramillo.minimalpairs.domain.model.SayItResults
import com.djaramillo.minimalpairs.domain.model.SayItWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The unpacked `sayit.zip` (docs/CONTRACT.md, "`sayit.zip`").
 *
 * The coach ships one file: `words.json`, `results.json` and `clips/<id>.ogg`
 * inside a zip that is overwritten in place forever, because the Drive service
 * account has no storage quota and can only PATCH a file that already exists.
 * The app never writes it. This class copies it out of the folder, unpacks it
 * into app-private storage and hands back the two parsed files; the screen
 * plays clips from the unpacked copy, so nothing in the mode needs the folder
 * again except to write the attempt.
 *
 * Unpacking is guarded. The zip arrives through a two-way sync from a cloud
 * service, so it is untrusted input: only the entry names [SayItZip.accepts]
 * allows are written, each one to a file the app names itself from the entry's
 * last segment rather than from the declared path, and the caps in [SayItZip]
 * stop a corrupt or hostile file from filling the phone. Nothing is ever
 * written outside [dir].
 *
 * It is also cheap to call. A stamp of the zip's size and modification time is
 * kept beside the unpacked copy, so a launch that finds the same zip as last
 * time reads two small files and stops.
 */
class SayItPack(private val filesDir: File) {

    /** `filesDir/sayit/` — the unpacked copy. Nothing else in the app writes here. */
    val dir: File = File(filesDir, "sayit")

    private val clipsDir: File get() = File(dir, "clips")
    private val stamp: File get() = File(dir, ".stamp")

    /**
     * One refresh at a time. This class is a process-wide singleton and both
     * its callers are live on the everyday path — the launch sync reads the
     * list while the owner taps "Say it" — so without this two unpacks would
     * run together, each deleting the other's staging tree and racing to
     * rename it over [dir]. The loser can publish a pack whose clips or
     * `results.json` are missing under a stamp that matches the folder's zip,
     * which the fast path below would then trust forever.
     */
    private val gate = Mutex()

    /** Names one run's scratch files apart from a run that died before cleaning up. */
    private val runs = AtomicLong()

    /**
     * What one refresh found. [words] and [results] are null when the entry was
     * absent or unparsable; [message] then says which, for Settings. [present]
     * is whether a `sayit.zip` was there at all, which is the difference
     * between "the list has not arrived yet" and "the list is broken".
     */
    data class Pack(
        val present: Boolean = false,
        val words: SayItWords? = null,
        val results: SayItResults? = null,
        val message: String? = null,
    )

    /**
     * Bring the unpacked copy up to date with the folder's `sayit.zip` and read
     * it. Never throws: any trouble comes back as a [Pack] with a message.
     *
     * When the zip is unchanged since the last refresh the unpacked copy is
     * reused as it stands. When it has changed, it is unpacked into a sibling
     * directory and swapped in, so a failure half way through leaves the
     * previous good copy in place rather than a half pack.
     *
     * Calls are serialised by [gate]: a second caller waits and then sees the
     * first one's finished work through the unchanged-zip fast path.
     */
    suspend fun refresh(folder: DataFolder): Pack = withContext(Dispatchers.IO) {
        gate.withLock { refreshLocked(folder) }
    }

    private suspend fun refreshLocked(folder: DataFolder): Pack {
        val info = folder.sayItZipInfo()
            ?: return readUnpacked(present = false, message = null)
        if (!info.readable) {
            return readUnpacked(
                present = true,
                message = "the folder would not say anything about sayit.zip; the list was left as it was",
            )
        }
        if (info.bytes > SayItZip.MAX_ZIP_BYTES) {
            return readUnpacked(
                present = true,
                message = "sayit.zip is ${info.bytes / (1024 * 1024)} MB, which is far past anything the coach sends; it was not unpacked",
            )
        }
        val want = "${info.bytes}:${info.modified}"
        if (currentStamp() == want && File(dir, SayItZip.WORDS).isFile) {
            return readUnpacked(present = true, message = null)
        }
        val run = "${System.currentTimeMillis()}-${runs.incrementAndGet()}"
        val staging = File(filesDir, STAGING_PREFIX + run)
        val copy = File(filesDir, COPY_PREFIX + run)
        sweepScratch(keep = setOf(staging.name, copy.name))
        try {
            if (!folder.copySayItZip(copy)) {
                return readUnpacked(present = true, message = "sayit.zip could not be read from the folder")
            }
            if (!staging.mkdirs()) {
                return readUnpacked(present = true, message = "the phone would not make room to unpack sayit.zip")
            }
            val trouble = unpack(copy, staging)
            if (trouble != null) {
                deleteTree(staging)
                return readUnpacked(present = true, message = trouble)
            }
            File(staging, ".stamp").writeText(want)
            deleteTree(dir)
            if (!staging.renameTo(dir)) {
                deleteTree(staging)
                return readUnpacked(present = true, message = "the unpacked sayit.zip could not be put in place")
            }
            return readUnpacked(present = true, message = null)
        } catch (e: Exception) {
            deleteTree(staging)
            return readUnpacked(present = true, message = "sayit.zip could not be unpacked: ${e.message?.take(100)}")
        } finally {
            copy.delete()
        }
    }

    /** The model clip for a `words.json` `clip` value, or null when there is none on the phone. */
    fun clip(relPath: String?): File? {
        val name = SayItZip.clipName(relPath?.trim().orEmpty()) ?: return null
        val f = File(clipsDir, name)
        return if (f.isFile && f.length() > 0) f else null
    }

    // ---- internals ------------------------------------------------------

    private fun currentStamp(): String? = try {
        if (stamp.isFile) stamp.readText().trim() else null
    } catch (e: IOException) {
        null
    }

    /**
     * Write the accepted entries of [zip] into [into]. Returns null on success
     * or a human message. Every entry is written to a name the app derives
     * itself, so a declared path can never decide where bytes land, and the
     * running total is counted as it is written rather than taken from the
     * zip's own header.
     *
     * An entry over [SayItZip.MAX_ENTRY_BYTES] is refused on its own, as the
     * contract says: its part file goes and the rest of the payload is still
     * unpacked, so one oversized clip costs the Play model button on one word
     * rather than the whole new word list. Its bytes stay in the running
     * total, so a zip bomb of such entries still trips the total cap, which is
     * the only breach worth abandoning the unpack for: continuing there would
     * fill the phone.
     */
    private fun unpack(zip: File, into: File): String? {
        val clips = File(into, "clips")
        if (!clips.mkdirs()) return "the phone would not make room for the model clips"
        var total = 0L
        var entries = 0
        try {
            ZipFile(zip).use { z ->
                val it = z.entries()
                while (it.hasMoreElements()) {
                    val entry = it.nextElement()
                    if (++entries > SayItZip.MAX_ENTRIES) return "sayit.zip holds more than ${SayItZip.MAX_ENTRIES} entries"
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!SayItZip.accepts(name)) continue
                    val target = when {
                        name == SayItZip.WORDS -> File(into, SayItZip.WORDS)
                        name == SayItZip.RESULTS -> File(into, SayItZip.RESULTS)
                        else -> File(clips, SayItZip.clipName(name) ?: continue)
                    }
                    // Belt and braces: the name came through accepts(), and the
                    // file must still resolve inside the directory we made.
                    if (!target.canonicalPath.startsWith(into.canonicalPath + File.separator)) continue
                    var written = 0L
                    var oversized = false
                    z.getInputStream(entry).use { source ->
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = source.read(buffer)
                                if (n < 0) break
                                written += n
                                total += n
                                if (total > SayItZip.MAX_TOTAL_BYTES) {
                                    return "sayit.zip unpacks to more than the app will hold; it was left alone"
                                }
                                if (written > SayItZip.MAX_ENTRY_BYTES) {
                                    oversized = true
                                    break
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    if (oversized) target.delete()
                }
            }
        } catch (e: ZipException) {
            return "sayit.zip is not a readable zip (${e.message?.take(80)})"
        } catch (e: IOException) {
            return "sayit.zip could not be unpacked (${e.message?.take(80)})"
        }
        return if (File(into, SayItZip.WORDS).isFile) null else "sayit.zip holds no words.json"
    }

    private fun readUnpacked(present: Boolean, message: String?): Pack {
        val words = decode(File(dir, SayItZip.WORDS), SayItWords.serializer())
        val results = decode(File(dir, SayItZip.RESULTS), SayItResults.serializer())
        val note = message
            ?: words.second
            ?: results.second
        return Pack(present = present, words = words.first, results = results.first, message = note)
    }

    /** (value, message): a file that is absent is simply null with no message. */
    private fun <T> decode(file: File, serializer: kotlinx.serialization.KSerializer<T>): Pair<T?, String?> {
        if (!file.isFile) return null to null
        return try {
            val text = file.readText()
            if (text.isBlank()) null to "${file.name} in sayit.zip is empty"
            else AppJson.json.decodeFromString(serializer, text) to null
        } catch (e: Exception) {
            null to "${file.name} in sayit.zip does not parse: ${e.message?.take(100)}"
        }
    }

    /**
     * Remove the scratch files of runs that are over, keeping [keep] — this
     * run's own. Scratch names carry a run marker so that two runs can never
     * share one, which means a run killed part way through (the process died,
     * the phone rebooted) leaves a directory nothing would otherwise collect.
     */
    private fun sweepScratch(keep: Set<String>) {
        val kids = try { filesDir.listFiles() } catch (e: SecurityException) { null } ?: return
        for (f in kids) {
            val name = f.name
            if (name in keep) continue
            if (name.startsWith(STAGING_PREFIX) || name.startsWith(COPY_PREFIX)) deleteTree(f)
        }
    }

    private fun deleteTree(f: File) {
        try {
            f.walkBottomUp().forEach { it.delete() }
        } catch (e: Exception) {
            // A leftover directory costs disk, never correctness: the next
            // refresh writes into a fresh staging directory either way.
        }
    }

    private companion object {
        /** `filesDir/sayit.unpacking<run>`: the tree being filled before the swap. */
        const val STAGING_PREFIX = "sayit.unpacking"

        /** `filesDir/sayit.zip.part<run>`: the local copy of the folder's zip. */
        const val COPY_PREFIX = "sayit.zip.part"
    }
}
