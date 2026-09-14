package com.djaramillo.minimalpairs.storage

/**
 * Pure name logic for the data folder (docs/DESIGN.md): the layout is always
 * `Documents/MinimalPairs/{plan.json, state.json, catalog-version.txt, sessions/}`
 * plus `sayit.zip`, `sayit/attempts/` and `sayit/scores/` for the Say-it drill
 * (docs/CONTRACT.md).
 * No Android types, unit tested on the JVM.
 */
object FolderLayout {
    const val SUBFOLDER = "MinimalPairs"
    const val DOCUMENTS = "Documents"
    const val PLAN = "plan.json"
    const val STATE = "state.json"
    const val STATE_TMP = "state.json.tmp"
    const val CATALOG_VERSION = "catalog-version.txt"
    const val SESSIONS = "sessions"

    // ---- Say it ---------------------------------------------------------

    /**
     * The Say-it names (docs/CONTRACT.md). The coach ships one file,
     * [SAYIT_ZIP], and the app reads it and never writes it; the app owns
     * `sayit/attempts/` and nothing else in the folder for this mode. These are
     * the coach interface, so they live here as constants rather than being
     * spelled out at each call site.
     */
    const val SAYIT_ZIP = "sayit.zip"
    const val SAYIT = "sayit"
    const val SAYIT_ATTEMPTS = "attempts"

    /**
     * `sayit/scores/` — one immutable `<ts>_<id>.json` per attempt the phone
     * scored, under the very same stem as the recording and its sidecar
     * (docs/CONTRACT.md, "`sayit/scores/`"). Never a shared mutable file: the
     * app still never writes `results.json`, which is the coach's history.
     */
    const val SAYIT_SCORES = "scores"

    /**
     * Which subfolder to create inside the picked tree, or null to use the tree
     * as is. Only a tree whose display name is `Documents` (any case) gets the
     * `MinimalPairs` child; anything else (including a folder already called
     * `MinimalPairs`) is used directly.
     */
    fun subfolderFor(treeDisplayName: String?): String? =
        if (treeDisplayName != null && treeDisplayName.trim().equals(DOCUMENTS, ignoreCase = true)) SUBFOLDER else null

    /**
     * Human-readable path for Settings from a tree document id such as
     * `primary:Documents` (→ `Documents`) or `1234-5678:Music` (→ `1234-5678/Music`),
     * plus the created subfolder when there is one.
     */
    fun displayPath(treeDocumentId: String?, subfolder: String?): String {
        val id = treeDocumentId ?: return subfolder ?: ""
        val colon = id.indexOf(':')
        val base = when {
            colon < 0 -> id
            id.substring(0, colon) == "primary" -> id.substring(colon + 1)
            else -> id.substring(0, colon) + "/" + id.substring(colon + 1)
        }.trim('/')
        val root = base.ifEmpty { "(root)" }
        return if (subfolder.isNullOrEmpty()) root else "$root/$subfolder"
    }

    /** `sessions/<id>.json` file name for a session id. */
    fun sessionFileName(sessionId: String): String = "$sessionId.json"

    /** Session id from a `sessions/` file name, or null when it is not one of ours. */
    fun sessionIdFromFileName(name: String): String? {
        if (!name.endsWith(".json")) return null
        val stem = name.removeSuffix(".json")
        return if (sessionIdPattern.matches(stem)) stem else null
    }

    private val sessionIdPattern = Regex("^\\d{8}T\\d{6}Z$")
}
