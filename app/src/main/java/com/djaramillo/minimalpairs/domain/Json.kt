package com.djaramillo.minimalpairs.domain

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for every contract file. Readers ignore unknown
 * keys, tolerate lenient input and treat an absent key as null; the contract
 * writer emits every key (nulls included, `"plan_written": null`) and every
 * default so a file is self-describing (`"version": 1`, all counters present)
 * and passes `scripts/validate-contract.py`, which requires the nullable keys
 * to be present.
 */
object AppJson {
    /** Reader for every file (`explicitNulls = false`: an absent key reads as null). */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * Writer for `state.json` and `sessions/<id>.json` (mirror and folder):
     * pretty-printed, every key present, nulls written as `null`.
     */
    val writer: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = true
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
