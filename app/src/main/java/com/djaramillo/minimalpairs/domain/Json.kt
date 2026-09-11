package com.djaramillo.minimalpairs.domain

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for every contract file. Readers ignore unknown
 * keys and tolerate lenient input; writers omit nulls and emit defaults so
 * every file is self-describing (`"version": 1` and all counters present).
 */
object AppJson {
    /** Pretty-printed: for `state.json` and `sessions/<id>.json` written to the folder. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Same settings, single line: for SharedPreferences or logs. */
    val compact: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }
}
