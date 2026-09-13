package com.djaramillo.minimalpairs.storage

import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.domain.SayItZip
import com.djaramillo.minimalpairs.domain.model.SayItResults
import com.djaramillo.minimalpairs.domain.model.SayItWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
     */
    suspend fun refresh(folder: DataFolder): Pack = withContext(Dispatchers.IO) {
        val info = folder.sayItZipInfo()
            ?: return@withContext readUnpacked(present = false, message = null)
        if (info.bytes > SayItZip.MAX_ZIP_BYTES) {
            return@withContext readUnpacked(
                present = true,
                message = "sayit.zip is ${info.bytes / (1024 * 1024)} MB, which is far past anything the coach sends; it was not unpacked",
            )
        }
        val want = "${info.bytes}:${info.modified}"
        if (currentStamp() == want && File(dir, SayItZip.WORDS).isFile) {
            return@withContext readUnpacked(present = true, message = null)
        }
        val staging = File(filesDir, "sayit.unpacking")
        val copy = File(filesDir, "sayit.zip.part")
        try {
            if (!folder.copySayItZip(copy)) {
                return@withContext readUnpacked(present = true, message = "sayit.zip could not be read from the folder")
            }
            deleteTree(staging)
            if (!staging.mkdirs()) {
                return@withContext readUnpacked(present = true, message = "the phone would not make room to unpack sayit.zip")
            }
            val trouble = unpack(copy, staging)
            if (trouble != null) {
                deleteTree(staging)
                return@withContext readUnpacked(present = true, message = trouble)
            }
            File(staging, ".stamp").writeText(want)
            deleteTree(dir)
            if (!staging.renameTo(dir)) {
                deleteTree(staging)
                return@withContext readUnpacked(present = true, message = "the unpacked sayit.zip could not be put in place")
            }
            readUnpacked(present = true, message = null)
        } catch (e: Exception) {
            deleteTree(staging)
            readUnpacked(present = true, message = "sayit.zip could not be unpacked: ${e.message?.take(100)}")
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
                    z.getInputStream(entry).use { source ->
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = source.read(buffer)
                                if (n < 0) break
                                written += n
                                total += n
                                if (written > SayItZip.MAX_ENTRY_BYTES || total > SayItZip.MAX_TOTAL_BYTES) {
                                    return "sayit.zip unpacks to more than the app will hold; it was left alone"
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
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

    private fun deleteTree(f: File) {
        try {
            f.walkBottomUp().forEach { it.delete() }
        } catch (e: Exception) {
            // A leftover directory costs disk, never correctness: the next
            // refresh writes into a fresh staging directory either way.
        }
    }
}
