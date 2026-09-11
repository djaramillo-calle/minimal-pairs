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
        val weightsSerializer = MapSerializer(String.serializer(), Double.serializer())
    }
}
