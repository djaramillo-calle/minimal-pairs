package com.djaramillo.minimalpairs.storage

import android.content.Context
import android.content.SharedPreferences
import com.djaramillo.minimalpairs.domain.AppJson
import com.djaramillo.minimalpairs.domain.model.Override
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The few settings that live outside the data folder: the SAF tree URI and the
 * learner's manual override (docs/ADAPTATION.md). Plain SharedPreferences.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("minimal-pairs", Context.MODE_PRIVATE)

    /** The tree URI the user picked (permission holder). */
    var treeUri: String?
        get() = sp.getString(KEY_TREE_URI, null)
        set(value) = sp.edit().putString(KEY_TREE_URI, value).apply()

    /** The document URI of the folder we actually write to (tree itself or its `MinimalPairs` child). */
    var rootUri: String?
        get() = sp.getString(KEY_ROOT_URI, null)
        set(value) = sp.edit().putString(KEY_ROOT_URI, value).apply()

    /** The subfolder we created inside the tree (`MinimalPairs`) or null when the tree is used as is. */
    var subfolder: String?
        get() = sp.getString(KEY_SUBFOLDER, null)
        set(value) = sp.edit().putString(KEY_SUBFOLDER, value).apply()

    fun clearFolder() {
        sp.edit().remove(KEY_TREE_URI).remove(KEY_ROOT_URI).remove(KEY_SUBFOLDER).apply()
    }

    /**
     * **This phone's own** Azure Speech credentials (Settings → Azure Speech):
     * a second, separate resource created for the phone alone, used to render
     * the clip pack and to score Say-it attempts on the spot
     * (docs/CONTRACT.md, "`sayit/scores/`").
     *
     * It is never the key the coach's cloud pipeline runs on. That key stays in
     * the cloud: a phone can be lost, lent or backed up, and the pipeline the
     * whole coaching system depends on must not go with it. Settings says so
     * under the field, and nothing in the app can tell the two apart, so the
     * separation lives in that instruction and in keeping this value where it
     * is — app-private storage, never backed up (`allowBackup=false`), never
     * written to the data folder, never logged.
     */
    var azureRegion: String?
        get() = sp.getString(KEY_AZURE_REGION, null)
        set(value) = sp.edit().putString(KEY_AZURE_REGION, value).apply()

    var azureKey: String?
        get() = sp.getString(KEY_AZURE_KEY, null)
        set(value) = sp.edit().putString(KEY_AZURE_KEY, value).apply()

    fun clearAzure() {
        sp.edit().remove(KEY_AZURE_REGION).remove(KEY_AZURE_KEY).apply()
    }

    /**
     * The catalog version whose clip pack has been published to the data folder
     * as `clips.zip`, or null when none has (docs/CONTRACT.md).
     *
     * The export is a ~60 MB write the user's Autosync then mirrors to Drive,
     * so it happens once per catalog version and not on every launch. It lives
     * in private preferences, which means a reinstall forgets it — which is
     * right: the app on the far side of a reinstall has no pack, and what it
     * does with the folder's `clips.zip` is install it.
     */
    var exportedPackCatalog: String?
        get() = sp.getString(KEY_EXPORTED_PACK_CATALOG, null)
        set(value) = sp.edit().putString(KEY_EXPORTED_PACK_CATALOG, value).apply()

    var override: Override
        get() {
            val enabled = sp.getBoolean(KEY_OVERRIDE_ENABLED, false)
            val trials = sp.getInt(KEY_OVERRIDE_TRIALS, -1).takeIf { it > 0 }
            val weights = sp.getString(KEY_OVERRIDE_WEIGHTS, null)?.let { text ->
                try {
                    AppJson.compact.decodeFromString(weightsSerializer, text)
                } catch (e: Exception) {
                    emptyMap()
                }
            } ?: emptyMap()
            return Override(enabled = enabled, weights = weights, trialsPerSession = trials)
        }
        set(value) {
            sp.edit()
                .putBoolean(KEY_OVERRIDE_ENABLED, value.enabled)
                .putInt(KEY_OVERRIDE_TRIALS, value.trialsPerSession ?: -1)
                .putString(KEY_OVERRIDE_WEIGHTS, AppJson.compact.encodeToString(weightsSerializer, value.weights))
                .apply()
        }

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_ROOT_URI = "root_uri"
        const val KEY_SUBFOLDER = "subfolder"
        const val KEY_OVERRIDE_ENABLED = "override_enabled"
        const val KEY_OVERRIDE_TRIALS = "override_trials"
        const val KEY_OVERRIDE_WEIGHTS = "override_weights"
        const val KEY_AZURE_REGION = "azure_region"
        const val KEY_AZURE_KEY = "azure_key"
        const val KEY_EXPORTED_PACK_CATALOG = "exported_pack_catalog"
        val weightsSerializer = MapSerializer(String.serializer(), Double.serializer())
    }
}
