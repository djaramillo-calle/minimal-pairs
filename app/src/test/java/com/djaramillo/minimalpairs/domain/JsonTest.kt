package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.ContrastSummary
import com.djaramillo.minimalpairs.domain.model.DayTally
import com.djaramillo.minimalpairs.domain.model.Practice
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.SessionRecord
import com.djaramillo.minimalpairs.domain.model.Summary
import com.djaramillo.minimalpairs.domain.model.TrialRow
import com.djaramillo.minimalpairs.domain.model.WordState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    private val json = AppJson.json

    private fun assertKeys(text: String, vararg keys: String) {
        for (k in keys) assertTrue("missing \"$k\" in:\n$text", text.contains("\"$k\""))
    }

    @Test
    fun catalogRoundTrip() {
        val text = json.encodeToString(Fixture.catalog)
        assertKeys(text, "version", "generated", "sources", "voices", "contrasts", "id", "label", "kind", "phonemes",
            "default_weight", "trainable", "production_only", "description", "pairs", "a", "b", "word", "ipa", "rank", "band",
            "diff", "variant", "position")
        val back = json.decodeFromString<Catalog>(text)
        assertEquals(Fixture.catalog, back)
        assertEquals("think / sink", back.contrast("th")!!.label)
        assertEquals(8, back.contrast("th")!!.trainablePairs.size)
        assertEquals(4, back.trainableContrasts.size)
    }

    @Test
    fun catalogParsesDesignSample() {
        val text = """
        {
          "version": "2026-09-11.1",
          "generated": "2026-09-11T10:00:00Z",
          "sources": {"pronunciation": "Britfone 3.0.1 (MIT)", "frequency": "hermitdave/FrequencyWords en_50k 2018 (MIT)"},
          "voices": ["en-GB-SoniaNeural"],
          "contrasts": [
            {
              "id": "i/ii", "label": "ship / sheep", "kind": "vowel",
              "phonemes": ["ɪ", "iː"], "default_weight": 0.6,
              "trainable": true, "production_only": false,
              "description": "Short ɪ versus long iː. Spanish has one /i/.",
              "pairs": [
                {
                  "id": "i/ii:ship-sheep",
                  "a": {"word": "ship",  "ipa": "ʃɪp",  "rank": 1834, "band": "high"},
                  "b": {"word": "sheep", "ipa": "ʃiːp", "rank": 3120, "band": "mid"},
                  "diff": ["ɪ", "iː"], "variant": "ɪ/iː", "position": "medial", "trainable": true
                },
                {
                  "id": "i/ii:x-y",
                  "a": {"word": "x", "ipa": "x", "rank": null, "band": "rare"},
                  "b": {"word": "y", "ipa": "y", "band": "rare"},
                  "diff": ["ɪ", "iː"], "variant": "ɪ/iː", "position": "medial", "trainable": false,
                  "future_key": 1
                }
              ]
            },
            {"id": "s-cluster", "label": "s-cluster", "kind": "consonant", "phonemes": ["s", ""],
             "default_weight": 0.0, "trainable": false, "production_only": true, "description": "", "pairs": []}
          ]
        }
        """.trimIndent()
        val c = json.decodeFromString<Catalog>(text)
        assertEquals(2, c.contrasts.size)
        assertEquals(1834, c.contrast("i/ii")!!.pairs[0].a.rank)
        assertNull(c.contrast("i/ii")!!.pairs[1].a.rank)
        assertEquals(setOf("ship", "sheep"), c.allTrainableWords())
    }

    @Test
    fun planParsesContractSampleAndEmpty() {
        val text = """
        {
          "version": 1,
          "written": "2026-09-11T18:30:00Z",
          "written_by": "coach",
          "note": "Week 3: th and s/z from the Tuesday recording. Keep the pace.",
          "trials_per_session": 40,
          "untrained_ratio": 0.5,
          "voices": ["en-GB-SoniaNeural", "en-GB-RyanNeural"],
          "band": ["high", "mid"],
          "feedback": "full",
          "weights": {"th": 1.0, "s/z": 0.8, "schwa": 0.15},
          "something_new": {"x": 1}
        }
        """.trimIndent()
        val p = json.decodeFromString<Plan>(text)
        assertEquals(1, p.version)
        assertEquals("coach", p.writtenBy)
        assertEquals(40, p.trialsPerSession)
        assertEquals(0.5, p.untrainedRatio!!, 1e-9)
        assertEquals(listOf("high", "mid"), p.band)
        assertEquals(0.15, p.weights!!.getValue("schwa"), 1e-9)

        val empty = json.decodeFromString<Plan>("{}")
        assertNull(empty.trialsPerSession)
        assertNull(empty.weights)

        val out = json.encodeToString(Plan(trialsPerSession = 30, untrainedRatio = 0.4, writtenBy = "coach", weights = mapOf("th" to 1.0)))
        assertKeys(out, "trials_per_session", "untrained_ratio", "written_by", "weights")
        assertFalse(out.contains("\"note\"")) // null omitted (explicitNulls = false)
    }

    @Test
    fun stateRoundTripAndEmpty() {
        val empty = json.decodeFromString<LearnerState>("{}")
        assertEquals(LearnerState(), empty)
        assertEquals(0, empty.sessionsCompleted)
        assertEquals(1, empty.version)

        val st = LearnerState(
            updated = "2026-09-11T07:05:30Z", appVersion = "0.1.0", catalogVersion = "2026-09-11.1",
            sessionsCompleted = 12, streakDays = 3, lastSession = "2026-09-11T07:02:11Z", planSource = "coach",
            contrasts = mapOf("th" to ContrastState(120, 98, 60, 45, 0.8, 0.75, listOf(0.6, 0.7, 0.75), 910, 34, 96)),
            words = mapOf("ship" to WordState(4, 3, "2026-09-11T07:03:10Z")),
        )
        val text = json.encodeToString(st)
        assertKeys(text, "version", "updated", "app_version", "catalog_version", "sessions_completed", "streak_days",
            "last_session", "plan_source", "contrasts", "trials", "correct", "untrained_trials", "untrained_correct",
            "last_pct", "last_untrained_pct", "recent_untrained_pct", "mean_rt_ms", "words_trained", "words_total",
            "words", "exposures", "last")
        assertEquals(st, json.decodeFromString<LearnerState>(text))
        // pretty printed with 2-space indent for the folder files
        assertTrue(text.startsWith("{\n  \"version\": 1"))

        // the contract sample parses
        val sample = """{"version": 1, "updated": "2026-09-11T07:05:30Z", "contrasts": {"th": {"trials": 120, "correct": 98,
          "untrained_trials": 60, "untrained_correct": 45, "last_pct": 0.8, "last_untrained_pct": null,
          "recent_untrained_pct": [0.6, 0.7, 0.75], "mean_rt_ms": 910, "words_trained": 34, "words_total": 96}},
          "words": {"ship": {"exposures": 4, "correct": 3, "last": "2026-09-11T07:03:10Z"}}}"""
        val s2 = json.decodeFromString<LearnerState>(sample)
        assertNull(s2.contrasts.getValue("th").lastUntrainedPct)
        assertEquals(4, s2.exposures("ship"))
        assertTrue(s2.isTrained("ship"))
        assertFalse(s2.isTrained("sheep"))
    }

    @Test
    fun sessionRecordRoundTripMatchesContractKeys() {
        val rec = SessionRecord(
            id = "20260911T070211Z", started = "2026-09-11T07:02:11Z", ended = "2026-09-11T07:05:28Z",
            appVersion = "0.1.0", catalogVersion = "2026-09-11.1", planSource = "coach", planWritten = "2026-09-11T18:30:00Z",
            voices = listOf("en-GB-SoniaNeural", "en-GB-RyanNeural"),
            trials = listOf(TrialRow(1, "th", "th:think-sink", "think", "sink", "sink", false, "en-GB-RyanNeural", 1210, 0, false, "high", "initial")),
            summary = Summary(40, 31, 0.775, 20, 14, 0.7, 197, 950, 0, mapOf("th" to ContrastSummary(10, 7, 5, 3, 1010))),
        )
        val text = json.encodeToString(rec)
        assertKeys(text, "version", "id", "started", "ended", "app_version", "catalog_version", "plan_source", "plan_written",
            "voices", "trials", "i", "contrast", "pair", "target", "other", "chosen", "correct", "voice", "rt_ms", "replays",
            "trained", "band", "position", "summary", "pct", "untrained_trials", "untrained_correct", "untrained_pct",
            "duration_s", "mean_rt_ms", "untrained_shortfall", "contrasts", "levels")
        assertEquals(rec, json.decodeFromString<SessionRecord>(text))

        val firstRow = Json.parseToJsonElement(text).jsonObject.getValue("trials").jsonArray[0].jsonObject
        assertEquals(
            listOf("i", "contrast", "pair", "target", "other", "chosen", "correct", "voice", "rt_ms", "replays", "trained", "band", "position"),
            firstRow.keys.toList(),
        )
        assertEquals("1210", firstRow.getValue("rt_ms").toString())

        // The reader config omits a null plan_written; the reader accepts the absent key as null.
        val noPlan = json.encodeToString(rec.copy(planWritten = null))
        assertFalse(noPlan.contains("plan_written"))
        assertNull(json.decodeFromString<SessionRecord>(noPlan).planWritten)
    }

    @Test
    fun writerEmitsEveryNullableContractKey() {
        // scripts/validate-contract.py requires the nullable keys to be present ("plan_written": null),
        // so the folder files are written with explicitNulls = true.
        val rec = SessionRecord(
            id = "20260911T070211Z", started = "2026-09-11T07:02:11Z", ended = "2026-09-11T07:05:28Z",
            appVersion = "0.1.0", catalogVersion = "2026-09-11.1", planSource = "default", planWritten = null,
            voices = listOf("en-GB-SoniaNeural"),
            trials = listOf(TrialRow(1, "th", "th:think-sink", "think", "sink", "think", true, "en-GB-SoniaNeural", 900, 0, true, "high", "initial")),
            summary = Summary(1, 1, 1.0, 0, 0, null, 197, 900, 1, mapOf("th" to ContrastSummary(1, 1, 0, 0, 900))),
        )
        val text = AppJson.writer.encodeToString(rec)
        assertTrue(text, text.contains("\"plan_written\": null"))
        assertTrue(text, text.contains("\"untrained_pct\": null"))
        assertEquals(rec, json.decodeFromString<SessionRecord>(text))
        assertEquals(rec, AppJson.writer.decodeFromString<SessionRecord>(text))

        val fresh = ContrastState(trials = 3, correct = 2, wordsTrained = 1, wordsTotal = 9)
        val st = LearnerState(updated = "2026-09-11T07:05:30Z", appVersion = "0.1.0", catalogVersion = "2026-09-11.1",
            sessionsCompleted = 1, streakDays = 1, lastSession = "2026-09-11T07:02:11Z", planSource = "default",
            contrasts = mapOf("th" to fresh))
        val stText = AppJson.writer.encodeToString(st)
        assertTrue(stText, stText.contains("\"last_untrained_pct\": null"))
        assertTrue(stText, stText.contains("\"last_pct\": null"))
        assertTrue(stText, stText.contains("\"mean_rt_ms\": null"))
        assertTrue(stText, stText.contains("\"level_changed\": null"))
        assertTrue(stText, stText.contains("\"practice\": {"))
        assertEquals(st, json.decodeFromString<LearnerState>(stText))
        assertTrue(stText.startsWith("{\n  \"version\": 1"))
    }

    @Test
    fun timeUtil() {
        val t = TimeUtil.parseIso("2026-09-11T07:02:11Z")!!
        assertEquals("2026-09-11T07:02:11Z", TimeUtil.formatIso(t))
        assertEquals("20260911T070211Z", TimeUtil.sessionIdFrom(t))
        assertEquals("2026-09-11", TimeUtil.utcDay(t).toString())
        assertEquals("2026-09-11T07:02:11Z", TimeUtil.formatIso(TimeUtil.parseIso("2026-09-11T07:02:11.987Z")!!))
        assertEquals("2026-09-11T06:02:11Z", TimeUtil.formatIso(TimeUtil.parseIso("2026-09-11T07:02:11+01:00")!!))
        assertNull(TimeUtil.parseIso(null))
        assertNull(TimeUtil.parseIso(""))
        assertNull(TimeUtil.parseIso("yesterday"))
        assertNull(TimeUtil.utcDay("nope"))
    }

    @Test
    fun firstFreeSessionStartBumpsPastTakenSeconds() {
        val t = TimeUtil.parseIso("2026-09-11T07:02:11.400Z")!!
        assertEquals("2026-09-11T07:02:11Z", TimeUtil.formatIso(TimeUtil.firstFreeSessionStart(t) { false }))
        val taken = setOf("20260911T070211Z", "20260911T070212Z")
        val free = TimeUtil.firstFreeSessionStart(t) { it in taken }
        assertEquals("20260911T070213Z", TimeUtil.sessionIdFrom(free))
        assertEquals("2026-09-11T07:02:13Z", TimeUtil.formatIso(free))
    }

    @Test
    fun levelsAndPracticeKeysRoundTrip() {
        val st = LearnerState(
            updated = "2026-09-11T07:05:30Z", sessionsCompleted = 12, streakDays = 3, lastSession = "2026-09-11T07:02:11Z",
            contrasts = mapOf("th" to ContrastState(
                trials = 120, correct = 98, untrainedTrials = 60, untrainedCorrect = 45, lastPct = 0.8, lastUntrainedPct = 0.75,
                recentUntrainedPct = listOf(0.6, 0.7, 0.75), meanRtMs = 910, wordsTrained = 34, wordsTotal = 96,
                level = 2, levelChanged = "2026-09-09T07:05:00Z",
            )),
            practice = Practice(longestStreak = 9, totalSeconds = 5400, days = mapOf("2026-09-11" to DayTally(1, 290, 40))),
        )
        val text = AppJson.writer.encodeToString(st)
        assertKeys(text, "level", "level_changed", "practice", "longest_streak",
            "total_seconds", "days", "sessions", "seconds", "perception_trials")
        assertEquals(st, json.decodeFromString<LearnerState>(text))
        val obj = Json.parseToJsonElement(text).jsonObject
        val th = obj.getValue("contrasts").jsonObject.getValue("th").jsonObject
        assertEquals("2", th.getValue("level").toString())
        val day = obj.getValue("practice").jsonObject.getValue("days").jsonObject.getValue("2026-09-11").jsonObject
        assertEquals(listOf("sessions", "seconds", "perception_trials"), day.keys.toList())

        // the contract sample parses, and {} still does
        val sample = """{"version": 1, "contrasts": {"th": {"trials": 1, "level": 3, "level_changed": null}},
          "practice": {"longest_streak": 9, "total_seconds": 5400,
            "days": {"2026-09-11": {"sessions": 1, "seconds": 290, "perception_trials": 40}}}}"""
        val s2 = json.decodeFromString<LearnerState>(sample)
        assertEquals(3, s2.contrasts.getValue("th").level)
        assertNull(s2.contrasts.getValue("th").levelChanged)
        assertEquals(9, s2.practice.longestStreak)
        assertEquals(40, s2.practice.days.getValue("2026-09-11").perceptionTrials)
        val fresh = json.decodeFromString<LearnerState>("{}")
        assertEquals(Practice(), fresh.practice)
        assertEquals(1, ContrastState().level)
        // a state written by the old build (production keys, pairs) still parses; the keys are ignored
        val old = json.decodeFromString<LearnerState>("""{"version":1,"contrasts":{"th":{"trials":2,"production_pairs":24}},
          "pairs":{"th:think-sink":{"attempts":3}}}""")
        assertEquals(2, old.contrasts.getValue("th").trials)
    }

    @Test
    fun levelsRoundTripAndOldRecordsStillParse() {
        val rec = SessionRecord(
            id = "20260911T070211Z", started = "2026-09-11T07:02:11Z", ended = "2026-09-11T07:05:28Z",
            appVersion = "0.2.0", catalogVersion = "2026-09-11.1", planSource = "coach", planWritten = "2026-09-11T18:30:00Z",
            voices = listOf("en-GB-SoniaNeural"),
            trials = listOf(TrialRow(1, "th", "th:think-sink", "think", "sink", "sink", false, "en-GB-RyanNeural", 1210, 0, false, "high", "initial")),
            levels = mapOf("th" to 2, "s/z" to 1),
            summary = Summary(
                trials = 40, correct = 31, pct = 0.775, untrainedTrials = 20, untrainedCorrect = 14, untrainedPct = 0.7,
                durationS = 292, meanRtMs = 950, untrainedShortfall = 0,
                contrasts = mapOf("th" to ContrastSummary(10, 7, 5, 3, 1010)),
            ),
        )
        val text = AppJson.writer.encodeToString(rec)
        assertEquals(rec, json.decodeFromString<SessionRecord>(text))
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf("version", "id", "started", "ended", "app_version", "catalog_version", "plan_source", "plan_written",
            "voices", "trials", "levels", "summary"), obj.keys.toList())
        val summary = obj.getValue("summary").jsonObject
        assertEquals("292", summary.getValue("duration_s").toString())
        assertFalse(text.contains("perception_duration_s"))
        assertFalse(text.contains("\"production\""))
        // a session written by the old build (production rows, perception_duration_s) still parses
        val old = json.decodeFromString<SessionRecord>("""{"version":1,"id":"x","started":"s","ended":"e","app_version":"a","catalog_version":"c",
          "plan_source":"default","production":null,"summary":{"trials":0,"correct":0,"pct":0.0,"untrained_trials":0,
          "untrained_correct":0,"duration_s":1,"perception_duration_s":1,"mean_rt_ms":0,"production":null}}""")
        assertEquals(emptyMap<String, Int>(), old.levels)
    }

    @Test
    fun planParsesTheLevelLevers() {
        val p = json.decodeFromString<Plan>("""{"version": 1, "max_level": 3,
          "levels": {"th": 2, "s/z": 4}, "weekly_minutes_target": 30}""")
        assertEquals(3, p.maxLevel)
        assertEquals(mapOf("th" to 2, "s/z" to 4), p.levels)
        assertEquals(30, p.weeklyMinutesTarget)
        val out = json.encodeToString(Plan(maxLevel = 4, levels = mapOf("th" to 1), weeklyMinutesTarget = 20))
        assertKeys(out, "max_level", "levels", "weekly_minutes_target")
        val empty = json.decodeFromString<Plan>("{}")
        assertNull(empty.maxLevel); assertNull(empty.levels); assertNull(empty.weeklyMinutesTarget)
        // a plan still carrying the old Say-it levers parses; the unknown keys are ignored
        assertEquals(3, json.decodeFromString<Plan>("""{"production_pairs": 8, "max_level": 3}""").maxLevel)
    }
}
