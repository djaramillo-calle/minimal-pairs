package com.djaramillo.minimalpairs.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.documentfile.provider.DocumentFile
import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.domain.SayItCleanup
import com.djaramillo.minimalpairs.domain.SayItNames
import com.djaramillo.minimalpairs.domain.TimeUtil
import com.djaramillo.minimalpairs.domain.model.AttemptSidecar
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.SayItResults
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
 * rewritten; a mirror session that has never reached the folder is republished
 * by [republishMissingSessions] (idempotent, called on launch and after a
 * session). A session that did reach the folder once is marked with an empty
 * `mirror/sessions/<id>.published` file and is not put back when it later
 * disappears from the folder: the coach may archive sessions on Drive and the
 * two-way mirror propagates that deletion to the phone (docs/CONTRACT.md).
 * The Settings "republish" button forces every missing session back.
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
        atomicWrite(mirrorState, AppJson.writer.encodeToString(LearnerState.serializer(), state))
    }

    /** Write `sessions/<id>.json` to the mirror; never overwrites. Returns the file. */
    suspend fun writeMirrorSession(record: SessionRecord): File = withContext(Dispatchers.IO) {
        mirrorSessions.mkdirs()
        val f = File(mirrorSessions, FolderLayout.sessionFileName(record.id))
        if (!f.exists()) atomicWrite(f, AppJson.writer.encodeToString(SessionRecord.serializer(), record))
        f
    }

    /**
     * The first second at or after [started] with no `mirror/sessions/<id>.json`
     * yet, so a session started in the same second as an earlier one (wall
     * clock stepped back) gets its own file instead of being dropped.
     */
    suspend fun freeSessionStart(started: java.time.Instant): java.time.Instant = withContext(Dispatchers.IO) {
        TimeUtil.firstFreeSessionStart(started) { id -> File(mirrorSessions, FolderLayout.sessionFileName(id)).exists() }
    }

    private fun publishedMarker(id: String): File = File(mirrorSessions, "$id$PUBLISHED_SUFFIX")

    /** Whether `sessions/<id>.json` has reached the folder at least once. */
    fun isPublished(id: String): Boolean = publishedMarker(id).isFile

    private fun markPublished(id: String) {
        try {
            mirrorSessions.mkdirs()
            val m = publishedMarker(id)
            if (!m.exists()) m.createNewFile()
        } catch (e: IOException) {
            // Worst case the session is republished once more; never fail the session for this.
        }
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
        val text = AppJson.writer.encodeToString(LearnerState.serializer(), state)
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

    /**
     * `sessions/<id>.json`, created once; an existing non-empty file is left
     * untouched (an empty one, left by an earlier write that failed after
     * `createFile`, is filled in). A successful outcome marks the session as
     * published so [republishMissingSessions] leaves it alone from then on.
     */
    suspend fun writeSession(record: SessionRecord): WriteOutcome = withContext(Dispatchers.IO) {
        val text = AppJson.writer.encodeToString(SessionRecord.serializer(), record)
        val outcome = writeSessionText(record.id, text)
        if (outcome == WriteOutcome.WRITTEN || outcome == WriteOutcome.ALREADY_THERE) markPublished(record.id)
        outcome
    }

    private fun writeSessionText(id: String, text: String): WriteOutcome {
        val root = root() ?: return WriteOutcome.FOLDER_UNAVAILABLE
        return try {
            val dir = findChild(root, FolderLayout.SESSIONS)?.takeIf { it.isDirectory }
                ?: root.createDirectory(FolderLayout.SESSIONS)
                ?: return WriteOutcome.FAILED
            val name = FolderLayout.sessionFileName(id)
            val existing = findChild(dir, name)
            if (existing != null) {
                if (!isEmptyFile(existing)) return WriteOutcome.ALREADY_THERE
                return if (writeText(existing.uri, text)) WriteOutcome.WRITTEN else WriteOutcome.FAILED
            }
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
     * Copy every `mirror/sessions/<id>.json` that has never reached the folder
     * (no `.published` marker) and is absent from — or empty in — the folder's
     * `sessions/`. With [force] every mirror session missing from the folder is
     * copied, published before or not (the Settings button). Idempotent.
     * Returns how many were published now, or null when the folder is unavailable.
     */
    suspend fun republishMissingSessions(force: Boolean = false): Int? = withContext(Dispatchers.IO) {
        val local = mirrorSessions.listFiles { f -> f.isFile && FolderLayout.sessionIdFromFileName(f.name) != null }
            ?.sortedBy { it.name } ?: emptyList()
        val candidates = if (force) local else local.filter { !isPublished(FolderLayout.sessionIdFromFileName(it.name)!!) }
        if (candidates.isEmpty()) return@withContext 0
        val root = root() ?: return@withContext null
        val dir = try {
            findChild(root, FolderLayout.SESSIONS)?.takeIf { it.isDirectory }
                ?: root.createDirectory(FolderLayout.SESSIONS)
        } catch (e: Exception) { null } ?: return@withContext null
        val present = try {
            dir.listFiles().filter { it.name != null }.associateBy { it.name!! }
        } catch (e: Exception) { return@withContext null }
        var published = 0
        for (f in candidates) {
            val id = FolderLayout.sessionIdFromFileName(f.name) ?: continue
            val existing = present[f.name]
            if (existing != null && !isEmptyFile(existing)) { markPublished(id); continue }
            val text = try { f.readText() } catch (e: IOException) { continue }
            if (existing != null) {
                // Zero-length leftover of a failed write: fill it in rather than leave it unparsable.
                if (writeText(existing.uri, text)) { published++; markPublished(id) }
                continue
            }
            val file = try { dir.createFile(MIME_BINARY, f.name) } catch (e: Exception) { null } ?: continue
            if (writeText(file.uri, text)) { published++; markPublished(id) } else file.delete()
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

    /**
     * Every child of [dir] as display name to document uri, read in one query.
     *
     * `DocumentFile.listFiles()` projects the document id alone, so each
     * child's name costs another `ContentResolver` round trip; asking for the
     * name in the projection answers the whole question once. That is the
     * difference between a few binder calls and several thousand of them in
     * `sayit/attempts/`, which grows for ever and is listed on every saved
     * recording. Throws what the provider throws: a caller that cannot tell an
     * empty folder from an unreadable one must catch it itself.
     */
    private fun childUris(dir: DocumentFile): Map<String, Uri> {
        val tree = dir.uri
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(tree),
        )
        val out = LinkedHashMap<String, Uri>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                out[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        return out
    }

    /** `DocumentFile.findFile` without the exception surface of a vanished tree. */
    private fun findChild(dir: DocumentFile, name: String): DocumentFile? = try {
        dir.listFiles().firstOrNull { it.name == name }
    } catch (e: Exception) {
        null
    }

    private fun safeExists(doc: DocumentFile): Boolean = try { doc.exists() } catch (e: Exception) { false }

    /** A zero-length document (a `createFile` whose content write failed) counts as missing. */
    private fun isEmptyFile(doc: DocumentFile): Boolean = try { doc.length() == 0L } catch (e: Exception) { false }

    // ---- Say it ---------------------------------------------------------

    /**
     * The Say-it sentence drill (docs/CONTRACT.md, "`sayit.zip`"). Everything
     * below obeys one rule: the coach owns `sayit.zip` — `words.json`,
     * `results.json` and every model clip live inside it — and this class opens
     * it **read-only**, copying it out for [SayItPack] to unpack into private
     * storage. It has no write path to it at all: no create, no rename, no
     * delete. The only files it ever creates are its own new
     * `sayit/attempts/<ts>_<id>.m4a` and `.json`, and the one deletion it makes
     * is the 30-day retention sweep in [sweepSayItAttempts], which removes
     * scored attempt audio and nothing else.
     *
     * There is no network call and no scoring anywhere in this section: the
     * app records, writes and displays, the cloud scores.
     */

    /**
     * Size and modification time of `sayit.zip`, so a refresh can tell it has
     * not changed. [readable] is false when the folder would not answer at
     * all, which is a broken zip and not the same thing as one the coach has
     * not sent yet: the caller must say so rather than show the "no list yet"
     * page.
     */
    data class SayItZipInfo(val bytes: Long, val modified: Long, val readable: Boolean = true)

    /** `sayit.zip` as the folder currently has it, or null when it is not there. */
    suspend fun sayItZipInfo(): SayItZipInfo? = withContext(Dispatchers.IO) {
        try {
            val root = root() ?: return@withContext null
            val doc = childUris(root)[FolderLayout.SAYIT_ZIP] ?: return@withContext null
            val file = DocumentFile.fromSingleUri(app, doc)?.takeIf { it.isFile } ?: return@withContext null
            SayItZipInfo(file.length(), file.lastModified())
        } catch (e: Exception) {
            SayItZipInfo(bytes = 0L, modified = 0L, readable = false)
        }
    }

    /**
     * Copy `sayit.zip` out of the folder into [dest], read-only.
     *
     * It is copied rather than read in place because it is unpacked with
     * `ZipFile`, which needs a seekable local file, and because the folder is a
     * two-way sync: a zip that is rewritten under us half way through a read is
     * ordinary here, and a local copy makes that a failed copy rather than a
     * corrupt unpack. Returns false on any trouble, leaving no half file.
     */
    suspend fun copySayItZip(dest: File): Boolean = withContext(Dispatchers.IO) {
        val doc = sayItZip() ?: return@withContext false
        try {
            dest.parentFile?.mkdirs()
            val copied = resolver.openInputStream(doc.uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out); out.flush() }
                true
            } ?: false
            if (!copied) dest.delete()
            copied
        } catch (e: Exception) {
            dest.delete()
            false
        }
    }

    /**
     * The first second at or after [started] whose attempt stem is free, so an
     * attempt made in a second that already has one (the wall clock stepped
     * back, or two attempts in one second) gets its own name and its `started`
     * moves with it — the name and the timestamp never disagree
     * (docs/CONTRACT.md).
     *
     * A stem counts as taken when **either** half is there, audio or sidecar:
     * the coach pairs them by stem, so reusing a stem whose partner survived
     * would splice two attempts into one.
     */
    suspend fun freeAttemptStart(id: String, started: java.time.Instant): java.time.Instant =
        withContext(Dispatchers.IO) {
            val taken = attemptFileNames().mapNotNullTo(HashSet<String>()) { name ->
                if (!SayItNames.isAttemptAudio(name) && !SayItNames.isAttemptSidecar(name)) return@mapNotNullTo null
                val ts = SayItNames.timestampOf(name) ?: return@mapNotNullTo null
                val word = SayItNames.idOf(name) ?: return@mapNotNullTo null
                SayItNames.stem(ts, word)
            }
            // freeStart asks about the stem, so one question covers both halves.
            SayItNames.freeStart(started, id) { stem -> stem in taken }
        }

    /**
     * Write one attempt: `sayit/attempts/<ts>_<id>.m4a` first, then the
     * sidecar `<ts>_<id>.json`. Returns the stem written, or null.
     *
     * The audio goes first because sync can deliver the two halves in either
     * order and the coach scores a stem only once both are there
     * (docs/CONTRACT.md). A half attempt must never reach it, so if the
     * sidecar fails the audio is deleted again and this returns null. `<ts>`
     * comes from [sidecar]'s own `started`, which is why the two can never
     * drift apart. An existing stem is a caller bug — [freeAttemptStart] is
     * there to avoid it — and is refused rather than overwritten, because an
     * attempt already in the folder may be one the coach has not scored yet.
     */
    suspend fun writeSayItAttempt(audio: File, sidecar: AttemptSidecar): String? = withContext(Dispatchers.IO) {
        val id = sidecar.id
        if (!SayItNames.isPracticableId(id)) return@withContext null
        val instant = TimeUtil.parseIso(sidecar.started) ?: return@withContext null
        val ts = TimeUtil.sessionIdFrom(instant)
        val audioName = SayItNames.audioName(ts, id)
        val sidecarName = SayItNames.sidecarName(ts, id)
        val text = AppJson.writer.encodeToString(AttemptSidecar.serializer(), sidecar)
        val readable = try { audio.isFile && audio.length() > 0L } catch (e: SecurityException) { false }
        if (!readable) return@withContext null
        var written: DocumentFile? = null
        try {
            val dir = attemptsDir(create = true) ?: return@withContext null
            // One listing answers both halves of the stem: the folder is large
            // and every extra walk of it is time the owner spends waiting.
            val present = childUris(dir)
            if (audioName in present || sidecarName in present) return@withContext null
            val audioDoc = dir.createFile(MIME_BINARY, audioName) ?: return@withContext null
            written = audioDoc
            if (!copyInto(audioDoc.uri, audio)) {
                audioDoc.delete()
                return@withContext null
            }
            val sidecarDoc = dir.createFile(MIME_BINARY, sidecarName)
            if (sidecarDoc == null || !writeText(sidecarDoc.uri, text)) {
                sidecarDoc?.delete()
                audioDoc.delete()
                return@withContext null
            }
            SayItNames.stem(ts, id)
        } catch (e: Exception) {
            try { written?.delete() } catch (e2: Exception) {
                // If the audio will not go now, the orphan half of
                // [sweepSayItAttempts] takes it later: audio with no sidecar
                // is the one thing no other rule can ever collect.
            }
            null
        }
    }

    /**
     * The retention sweep (docs/CONTRACT.md): delete the attempt audio that is
     * more than 30 days old **and** already scored in [results], and the
     * orphaned audio that has no sidecar beside it. Returns how many files
     * went, or null when the folder is unavailable.
     *
     * `SayItCleanup` decides both; this only carries the decision out, and it
     * re-checks that every name is attempt audio before deleting. The orphans
     * are the leftovers of a write whose sidecar never landed: the coach
     * cannot score a recording it has no sidecar for and reports it as an
     * error on every run, and the retention rule can never reach it, because
     * that rule asks whether the sidecar has been scored. The sidecars stay as
     * the record of the attempt, an unscored recording that has one is never
     * deleted however old, and nothing outside `sayit/attempts/` is touched:
     * `clips/`, `words.json` and `results.json` are the coach's.
     */
    suspend fun sweepSayItAttempts(results: SayItResults?, now: java.time.Instant): Int? =
        withContext(Dispatchers.IO) {
            try {
                if (root() == null) return@withContext null
                val dir = attemptsDir(create = false) ?: return@withContext 0
                val present = childUris(dir)
                val names = present.keys.toList()
                var removed = 0
                val going = (SayItCleanup.deletable(names, results, now) + SayItCleanup.orphans(names, now)).distinct()
                for (name in going) {
                    if (!SayItNames.isAttemptAudio(name)) continue
                    val uri = present[name] ?: continue
                    val gone = try { DocumentsContract.deleteDocument(resolver, uri) } catch (e: Exception) { false }
                    if (gone) removed++
                }
                removed
            } catch (e: Exception) {
                null
            }
        }

    // ---- Say it helpers -------------------------------------------------

    // ---- Say it helpers -------------------------------------------------

    /** `sayit.zip` in the folder root, or null. Never created by the app. */
    private fun sayItZip(): DocumentFile? = try {
        val root = root()
        if (root == null) null else findChild(root, FolderLayout.SAYIT_ZIP)?.takeIf { it.isFile }
    } catch (e: Exception) {
        null
    }

    /**
     * `sayit/attempts/`, the one folder the app owns. With [create] it and its
     * `sayit/` parent are made when missing; without, a missing folder is null
     * and the caller treats it as "no attempts".
     */
    private fun attemptsDir(create: Boolean): DocumentFile? = try {
        val root = root()
        val sayit = if (root == null) null else {
            findChild(root, FolderLayout.SAYIT)?.takeIf { it.isDirectory }
                ?: if (create) root.createDirectory(FolderLayout.SAYIT) else null
        }
        if (sayit == null) null else {
            findChild(sayit, FolderLayout.SAYIT_ATTEMPTS)?.takeIf { it.isDirectory }
                ?: if (create) sayit.createDirectory(FolderLayout.SAYIT_ATTEMPTS) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Every name in `sayit/attempts/`, listed once; empty when the folder is unavailable. */
    private fun attemptFileNames(): List<String> = try {
        attemptsDir(create = false)?.let { childUris(it).keys.toList() } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Stream [source] into [uri]. An attempt is up to 20 seconds of AAC, so it
     * is copied rather than read into memory; the mode dance matches
     * [writeText], where some providers reject `"wt"`.
     */
    private fun copyInto(uri: Uri, source: File): Boolean = try {
        val out = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(uri)
        } ?: resolver.openOutputStream(uri)
        out?.use { o -> source.inputStream().use { it.copyTo(o) }; o.flush(); true } ?: false
    } catch (e: Exception) {
        false
    }

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /** `mirror/sessions/<id>.published`: empty marker, the session reached the folder once. */
        const val PUBLISHED_SUFFIX = ".published"


        /**
         * `application/octet-stream` keeps the display name exactly as given:
         * with a specific MIME type the external storage provider appends the
         * MIME's extension when it does not match (`state.json.tmp` → `.json`).
         * The provider reports the type from the extension on query anyway.
         */
        const val MIME_BINARY = "application/octet-stream"
    }
}
