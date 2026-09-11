package com.djaramillo.minimalpairs.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.documentfile.provider.DocumentFile
import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The coach folder `Documents/MinimalPairs/` (docs/CONTRACT.md) through the
 * Storage Access Framework, plus the private mirror under `filesDir/mirror/`.
 *
 * Ownership: the mirror is the app's source of truth for `state.json`; the
 * folder holds the published copy. Session files are written once and never
 * rewritten; any mirror session missing from the folder is republished by
 * [republishMissingSessions] (idempotent, called on launch and after a session).
 *
 * Every public suspend function runs on [Dispatchers.IO] and never throws for
 * folder trouble: results carry a status instead.
 */
class DataFolder(context: Context, private val prefs: Prefs) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** The mirror: `filesDir/mirror/state.json` and `filesDir/mirror/sessions/<id>.json`. */
    val mirrorDir: File = File(app.filesDir, "mirror")
    val mirrorState: File = File(mirrorDir, FolderLayout.STATE)
    val mirrorSessions: File = File(mirrorDir, FolderLayout.SESSIONS)

    sealed class Status {
        data object NotChosen : Status()
        data class Ok(val path: String) : Status()
        /** The persisted permission is gone (revoked, app data restored, folder removed). */
        data object PermissionLost : Status()
        /** Permission is there but the folder cannot be written. */
        data object Unwritable : Status()
    }

    /** Result of reading `plan.json`. */
    data class PlanResult(val plan: Plan?, val message: String?)

    /** Where a file ended up. */
    enum class WriteOutcome { WRITTEN, ALREADY_THERE, FOLDER_UNAVAILABLE, FAILED }

    // ---- picking --------------------------------------------------------

    /**
     * `ACTION_OPEN_DOCUMENT_TREE` starting at `Documents`, asking for read +
     * write + persistable grants. Use with `rememberLauncherForActivityResult`.
     */
    class PickFolder : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Documents"),
                )
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
            }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }

    /**
     * Adopt the tree the user picked: persist the grant, create/find the
     * `MinimalPairs` child when the tree is `Documents` itself, remember both
     * URIs. Returns the resulting [Status].
     */
    suspend fun onFolderPicked(treeUri: Uri): Status = withContext(Dispatchers.IO) {
        try {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            return@withContext Status.PermissionLost
        }
        // Drop the previous grant if it was a different tree.
        val previous = prefs.treeUri
        if (previous != null && previous != treeUri.toString()) {
            try {
                resolver.releasePersistableUriPermission(
                    Uri.parse(previous),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // Already gone; nothing to release.
            }
        }
        val tree = DocumentFile.fromTreeUri(app, treeUri) ?: return@withContext Status.Unwritable
        val subfolder = FolderLayout.subfolderFor(tree.name)
        val root = if (subfolder == null) tree else {
            findChild(tree, subfolder)?.takeIf { it.isDirectory }
                ?: tree.createDirectory(subfolder)
                ?: return@withContext Status.Unwritable
        }
        prefs.treeUri = treeUri.toString()
        prefs.rootUri = root.uri.toString()
        prefs.subfolder = subfolder
        status()
    }

    /** Forget the folder (keeps the mirror). */
    suspend fun forgetFolder() = withContext(Dispatchers.IO) {
        prefs.treeUri?.let {
            try {
                resolver.releasePersistableUriPermission(
                    Uri.parse(it),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // ignore
            }
        }
        prefs.clearFolder()
    }

    // ---- status ---------------------------------------------------------

    val isChosen: Boolean get() = prefs.treeUri != null

    /** Human-readable path for Settings / Summary, without touching the provider. */
    fun displayPath(): String? {
        val tree = prefs.treeUri ?: return null
        val id = try { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) } catch (e: IllegalArgumentException) { null }
        return FolderLayout.displayPath(id, prefs.subfolder)
    }

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val treeText = prefs.treeUri ?: return@withContext Status.NotChosen
        val treeUri = Uri.parse(treeText)
        val granted = resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return@withContext Status.PermissionLost
        val root = root() ?: return@withContext Status.Unwritable
        val ok = try { root.isDirectory && root.canWrite() } catch (e: Exception) { false }
        if (ok) Status.Ok(displayPath() ?: FolderLayout.SUBFOLDER) else Status.Unwritable
    }

    /**
     * The folder we write to, re-resolved on every call (cheap: one document
     * query). Recreates `MinimalPairs` under `Documents` if it was deleted.
     */
    private fun root(): DocumentFile? {
        val treeText = prefs.treeUri ?: return null
        val rootText = prefs.rootUri
        if (rootText != null) {
            val doc = DocumentFile.fromTreeUri(app, Uri.parse(rootText))
            if (doc != null && safeExists(doc)) return doc
        }
        // Root gone: fall back to the tree and (re)create the subfolder.
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(treeText)) ?: return null
        if (!safeExists(tree)) return null
        val sub = prefs.subfolder ?: return tree
        val child = findChild(tree, sub)?.takeIf { it.isDirectory } ?: tree.createDirectory(sub) ?: return null
        prefs.rootUri = child.uri.toString()
        return child
    }

    // ---- reading --------------------------------------------------------

    /** `plan.json`: absent → (null, null); unparsable → (null, message). Never throws. */
    suspend fun readPlan(): PlanResult = withContext(Dispatchers.IO) {
        val root = root() ?: return@withContext PlanResult(null, null)
        val file = findChild(root, FolderLayout.PLAN) ?: return@withContext PlanResult(null, null)
        val text = try {
            resolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            return@withContext PlanResult(null, "plan.json could not be read: ${e.message}")
        } ?: return@withContext PlanResult(null, "plan.json could not be opened")
        try {
            PlanResult(AppJson.json.decodeFromString(Plan.serializer(), text), null)
        } catch (e: Exception) {
            PlanResult(null, "plan.json does not parse; using defaults (${e.message?.take(120)})")
        }
    }

    /** The folder's `state.json`, only used to adopt it on first run when there is no mirror. */
    suspend fun readFolderState(): LearnerState? = withContext(Dispatchers.IO) {
        val root = root() ?: return@withContext null
        val file = findChild(root, FolderLayout.STATE) ?: return@withContext null
        try {
            resolver.openInputStream(file.uri)?.use { s ->
                AppJson.json.decodeFromString(LearnerState.serializer(), s.readBytes().toString(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- mirror ---------------------------------------------------------

    suspend fun readMirrorState(): LearnerState? = withContext(Dispatchers.IO) {
        if (!mirrorState.isFile) return@withContext null
        try {
            AppJson.json.decodeFromString(LearnerState.serializer(), mirrorState.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Write `state.json` to the mirror (tmp + rename). Throws on I/O failure: the mirror must work. */
    suspend fun writeMirrorState(state: LearnerState) = withContext(Dispatchers.IO) {
        mirrorDir.mkdirs()
        atomicWrite(mirrorState, AppJson.json.encodeToString(LearnerState.serializer(), state))
    }

    /** Write `sessions/<id>.json` to the mirror; never overwrites. Returns the file. */
    suspend fun writeMirrorSession(record: SessionRecord): File = withContext(Dispatchers.IO) {
        mirrorSessions.mkdirs()
        val f = File(mirrorSessions, FolderLayout.sessionFileName(record.id))
        if (!f.exists()) atomicWrite(f, AppJson.json.encodeToString(SessionRecord.serializer(), record))
        f
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }
    }

    // ---- writing to the folder -----------------------------------------

    /** `state.json` via `state.json.tmp` → delete old → rename. */
    suspend fun writeState(state: LearnerState): WriteOutcome = withContext(Dispatchers.IO) {
        val root = root() ?: return@withContext WriteOutcome.FOLDER_UNAVAILABLE
        val text = AppJson.json.encodeToString(LearnerState.serializer(), state)
        try {
            findChild(root, FolderLayout.STATE_TMP)?.delete()
            val tmp = root.createFile(MIME_BINARY, FolderLayout.STATE_TMP)
                ?: return@withContext WriteOutcome.FAILED
            if (!writeText(tmp.uri, text)) { tmp.delete(); return@withContext WriteOutcome.FAILED }
            findChild(root, FolderLayout.STATE)?.delete()
            val renamed = try {
                DocumentsContract.renameDocument(resolver, tmp.uri, FolderLayout.STATE) != null
            } catch (e: Exception) {
                false
            }
            if (renamed) return@withContext WriteOutcome.WRITTEN
            // Rename unsupported: write the final file directly and drop the tmp.
            val direct = findChild(root, FolderLayout.STATE) ?: root.createFile(MIME_BINARY, FolderLayout.STATE)
            val ok = direct != null && writeText(direct.uri, text)
            tmp.delete()
            if (ok) WriteOutcome.WRITTEN else WriteOutcome.FAILED
        } catch (e: Exception) {
            WriteOutcome.FAILED
        }
    }

    /** `sessions/<id>.json`, created once; an existing file is left untouched. */
    suspend fun writeSession(record: SessionRecord): WriteOutcome = withContext(Dispatchers.IO) {
        val text = AppJson.json.encodeToString(SessionRecord.serializer(), record)
        writeSessionText(record.id, text)
    }

    private fun writeSessionText(id: String, text: String): WriteOutcome {
        val root = root() ?: return WriteOutcome.FOLDER_UNAVAILABLE
        return try {
            val dir = findChild(root, FolderLayout.SESSIONS)?.takeIf { it.isDirectory }
                ?: root.createDirectory(FolderLayout.SESSIONS)
                ?: return WriteOutcome.FAILED
            val name = FolderLayout.sessionFileName(id)
            if (findChild(dir, name) != null) return WriteOutcome.ALREADY_THERE
            val file = dir.createFile(MIME_BINARY, name) ?: return WriteOutcome.FAILED
            if (writeText(file.uri, text)) WriteOutcome.WRITTEN else { file.delete(); WriteOutcome.FAILED }
        } catch (e: Exception) {
            WriteOutcome.FAILED
        }
    }

    /** `catalog-version.txt`, overwritten on every launch. */
    suspend fun writeCatalogVersion(version: String): WriteOutcome = withContext(Dispatchers.IO) {
        val root = root() ?: return@withContext WriteOutcome.FOLDER_UNAVAILABLE
        try {
            val text = version.trim() + "\n"
            val existing = findChild(root, FolderLayout.CATALOG_VERSION)
            if (existing != null) {
                val current = try {
                    resolver.openInputStream(existing.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) { null }
                if (current == text) return@withContext WriteOutcome.ALREADY_THERE
                if (writeText(existing.uri, text)) return@withContext WriteOutcome.WRITTEN
                existing.delete()
            }
            val created = root.createFile(MIME_BINARY, FolderLayout.CATALOG_VERSION)
                ?: return@withContext WriteOutcome.FAILED
            if (writeText(created.uri, text)) WriteOutcome.WRITTEN else WriteOutcome.FAILED
        } catch (e: Exception) {
            WriteOutcome.FAILED
        }
    }

    /**
     * Copy every `mirror/sessions/<id>.json` that the folder's `sessions/`
     * lacks. Idempotent. Returns how many were published now, or null when the
     * folder is unavailable.
     */
    suspend fun republishMissingSessions(): Int? = withContext(Dispatchers.IO) {
        val local = mirrorSessions.listFiles { f -> f.isFile && FolderLayout.sessionIdFromFileName(f.name) != null }
            ?.sortedBy { it.name } ?: emptyList()
        if (local.isEmpty()) return@withContext 0
        val root = root() ?: return@withContext null
        val dir = try {
            findChild(root, FolderLayout.SESSIONS)?.takeIf { it.isDirectory }
                ?: root.createDirectory(FolderLayout.SESSIONS)
        } catch (e: Exception) { null } ?: return@withContext null
        val present = try {
            dir.listFiles().mapNotNull { it.name }.toHashSet()
        } catch (e: Exception) { return@withContext null }
        var published = 0
        for (f in local) {
            if (f.name in present) continue
            val text = try { f.readText() } catch (e: IOException) { continue }
            val file = try { dir.createFile(MIME_BINARY, f.name) } catch (e: Exception) { null } ?: continue
            if (writeText(file.uri, text)) published++ else file.delete()
        }
        published
    }

    // ---- helpers --------------------------------------------------------

    private fun writeText(uri: Uri, text: String): Boolean = try {
        val out = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            // Some providers reject the "wt" mode string; plain "w" truncates on ExternalStorageProvider too.
            resolver.openOutputStream(uri)
        } ?: resolver.openOutputStream(uri)
        out?.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush(); true } ?: false
    } catch (e: Exception) {
        false
    }

    /** `DocumentFile.findFile` without the exception surface of a vanished tree. */
    private fun findChild(dir: DocumentFile, name: String): DocumentFile? = try {
        dir.listFiles().firstOrNull { it.name == name }
    } catch (e: Exception) {
        null
    }

    private fun safeExists(doc: DocumentFile): Boolean = try { doc.exists() } catch (e: Exception) { false }

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /**
         * `application/octet-stream` keeps the display name exactly as given:
         * with a specific MIME type the external storage provider appends the
         * MIME's extension when it does not match (`state.json.tmp` → `.json`).
         * The provider reports the type from the extension on query anyway.
         */
        const val MIME_BINARY = "application/octet-stream"
    }
}
