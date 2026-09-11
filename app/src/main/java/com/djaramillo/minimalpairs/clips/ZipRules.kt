package com.djaramillo.minimalpairs.clips

/**
 * Validation of `clips.zip` entries before anything touches the file system.
 * Pure (no Android / java.util.zip types) so it is unit tested on the JVM.
 *
 * Accepted layout (what `scripts/render-clips.py --zip` and CI's `zip -r` produce):
 * `index.json`, `sha256.txt`, `<voice>/<word>.webm`. A leading `./` or `clips/`
 * is tolerated and stripped.
 */
object ZipRules {
    /** Total uncompressed bytes allowed for one pack. */
    const val MAX_TOTAL_BYTES: Long = 200L * 1024 * 1024
    /** Any single entry above this is refused. */
    const val MAX_ENTRY_BYTES: Long = 8L * 1024 * 1024
    /** Sanity cap on the number of files. */
    const val MAX_ENTRIES: Int = 30_000

    const val INDEX = "index.json"
    const val SHA256 = "sha256.txt"

    sealed class Verdict {
        /** Write the entry to `relativePath` (already normalised, forward slashes). */
        data class Accept(val relativePath: String) : Verdict()
        /** Directory entry or harmless extra: ignore silently. */
        data object Skip : Verdict()
        /** Abort the whole unzip. */
        data class Reject(val reason: String) : Verdict()
    }

    private val voiceSegment = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val wordSegment = Regex("^[a-z]{1,40}$")

    /** Normalise a zip entry name: backslashes, `./` and a single leading `clips/`. */
    fun normalise(name: String): String {
        var n = name.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        if (n.startsWith("clips/")) n = n.substring("clips/".length)
        return n
    }

    fun judge(entryName: String, isDirectory: Boolean): Verdict {
        if (isDirectory) return Verdict.Skip
        val raw = entryName
        if (raw.isEmpty()) return Verdict.Reject("empty entry name")
        if (raw.any { it < ' ' }) return Verdict.Reject("control character in entry name")
        val n = normalise(raw)
        if (n.isEmpty()) return Verdict.Skip
        if (n.startsWith("/")) return Verdict.Reject("absolute path: $raw")
        if (n.contains("//")) return Verdict.Reject("empty path segment: $raw")
        val parts = n.split('/')
        if (parts.any { it == ".." || it == "." }) return Verdict.Reject("path traversal: $raw")
        if (parts.any { it.contains(':') }) return Verdict.Reject("drive or colon in name: $raw")
        return when (parts.size) {
            1 -> when (parts[0]) {
                INDEX, SHA256 -> Verdict.Accept(parts[0])
                else -> Verdict.Reject("unexpected top-level file: $raw")
            }
            2 -> {
                val (voice, file) = parts
                if (!voiceSegment.matches(voice)) return Verdict.Reject("bad voice folder: $raw")
                if (!file.endsWith(".webm")) return Verdict.Reject("not a .webm clip: $raw")
                val word = file.removeSuffix(".webm")
                if (!wordSegment.matches(word)) return Verdict.Reject("bad clip name: $raw")
                Verdict.Accept("$voice/$file")
            }
            else -> Verdict.Reject("unexpected depth: $raw")
        }
    }

    /**
     * Parse `sha256.txt` (`<hex>  <voice>/<word>.webm` per line, sha256sum
     * style; a `*` binary marker is tolerated). Malformed lines are ignored.
     */
    fun parseSha256(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val sp = t.indexOfFirst { it == ' ' || it == '\t' }
            if (sp != 64) continue
            val hex = t.substring(0, 64).lowercase()
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) continue
            var path = t.substring(sp).trim()
            if (path.startsWith("*")) path = path.substring(1)
            path = normalise(path)
            if (path.isEmpty()) continue
            out[path] = hex
        }
        return out
    }
}
