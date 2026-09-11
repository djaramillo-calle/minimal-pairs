package com.djaramillo.minimalpairs.clips

import com.djaramillo.minimalpairs.domain.ProductionScorer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.Base64

/**
 * The Azure Speech-to-text REST calls of the Say-it block (docs/CONTRACT.md
 * "Say it — production rows"), made with the learner's own key from Settings:
 * two en-US pronunciation assessments (reference = the intended word, then
 * the other word; IPA phonemes) and one en-GB recognition without reference.
 * The three run concurrently per recording ([assessPair]).
 *
 * Same conventions as [AzureTts]: the key only ever goes into the
 * `Ocp-Apim-Subscription-Key` header, never into a log or an exception;
 * transient failures (429, 5xx, network) are retried [ATTEMPTS] times,
 * problems that will not go away ([Fatal]: bad key or region, unreachable
 * host) end the block.
 */
class AzureSpeech(private val region: String, private val key: String) {

    /** One parsed response: `NBest[0]` of `format=detailed`. */
    data class Result(
        /** `RecognitionStatus`: `Success`, `NoMatch`, `InitialSilenceTimeout`, … */
        val status: String,
        /** Word-level `AccuracyScore` (0–100) of the assessed word; `null` on a recognition call or when nothing matched. */
        val acc: Double?,
        /** `Words[0].Phonemes` as (symbol, AccuracyScore), in order; empty when absent. */
        val phonemes: List<Pair<String, Double>>,
        /** `NBest[0].Lexical` lower-cased, punctuation stripped; `null` when nothing was recognised. */
        val recognised: String?,
    ) {
        val success: Boolean get() = status == STATUS_SUCCESS
    }

    /** The three signals for one recording. */
    data class PairResult(val intended: Result, val other: Result, val recognition: Result) {
        /**
         * Azure heard nothing at all: not one of the three calls came back
         * `Success` (`NoMatch`, `InitialSilenceTimeout`, …), or both
         * assessments scored 0 and the recognition returned no text — what a
         * breath, a chair or a page turn gives. Such a recording holds no
         * speech however the local gate judged it, so it may be redone once
         * like any other (docs/CONTRACT.md "Say it", `attempts`).
         */
        val heardNothing: Boolean
            get() = (!intended.success && !other.success && !recognition.success) ||
                ((intended.acc ?: 0.0) <= 0.0 && (other.acc ?: 0.0) <= 0.0 && recognition.recognised == null)
    }

    /** en-US pronunciation assessment of [wav] against [referenceText]. */
    suspend fun assess(wav: ByteArray, referenceText: String): Result =
        call(wav, ASSESSMENT_LANGUAGE, assessmentHeader(referenceText))

    /** en-GB recognition of [wav] without a reference. */
    suspend fun recognise(wav: ByteArray): Result = call(wav, RECOGNITION_LANGUAGE, null)

    /** The three calls at once; fails as a whole if any of them fails for good. */
    suspend fun assessPair(wav: ByteArray, intended: String, other: String): PairResult = coroutineScope {
        val a = async { assess(wav, intended) }
        val b = async { assess(wav, other) }
        val r = async { recognise(wav) }
        PairResult(a.await(), b.await(), r.await())
    }

    /**
     * Whether the STT host can be reached at all (DNS + TCP); any HTTP answer
     * counts. Cheap enough to run while the block is prepared, so a phone
     * without a connection gets the "skipped" notice before recording anything.
     */
    suspend fun reachable(): Boolean {
        val c = try {
            URL("https://$region.stt.speech.microsoft.com/").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return false
        }
        c.requestMethod = "GET"
        c.connectTimeout = PROBE_TIMEOUT_MS
        c.readTimeout = PROBE_TIMEOUT_MS
        c.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            c.responseCode
            true
        } catch (e: IOException) {
            false
        } finally {
            c.disconnect()
        }
    }

    private suspend fun call(wav: ByteArray, language: String, assessmentHeader: String?): Result {
        var last: IOException? = null
        for (attempt in 1..ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                return callOnce(wav, language, assessmentHeader)
            } catch (e: Transient) {
                last = e
                if (attempt < ATTEMPTS) delay(RenderPlan.retryDelayMs(attempt, e.retryAfter))
            }
        }
        throw last ?: IOException("speech request failed")
    }

    private fun callOnce(wav: ByteArray, language: String, assessmentHeader: String?): Result {
        val c = open(endpoint(region, language))
        c.doOutput = true
        c.setRequestProperty("Content-Type", CONTENT_TYPE)
        c.setRequestProperty("Accept", "application/json")
        if (assessmentHeader != null) c.setRequestProperty("Pronunciation-Assessment", assessmentHeader)
        c.setFixedLengthStreamingMode(wav.size)
        try {
            val code = try {
                c.outputStream.use { it.write(wav) }
                c.responseCode
            } catch (e: UnknownHostException) {
                throw Fatal("cannot reach $region.stt.speech.microsoft.com: check the region and the connection")
            } catch (e: IOException) {
                throw Transient("network: ${e.javaClass.simpleName}", null)
            }
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val body = try { c.inputStream.bufferedReader().use { it.readText() } } catch (e: IOException) {
                        throw Transient("network: ${e.javaClass.simpleName}", null)
                    }
                    return parse(body) ?: throw Transient("unreadable answer from Azure", null)
                }
                code == 429 || code >= 500 -> {
                    val ra = c.getHeaderField("Retry-After")
                    val secs = ra?.trim()?.toLongOrNull()
                    if (secs != null && secs > AzureTts.MAX_RETRY_AFTER_S) {
                        throw Fatal("${AzureTts.describe(code)}; Azure asks to wait $secs s (quota exhausted?). Try again later")
                    }
                    throw Transient(AzureTts.describe(code), ra)
                }
                code == 401 || code == 403 || code == 404 -> throw Fatal(AzureTts.describe(code))
                else -> throw IOException(AzureTts.describe(code))
            }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.setRequestProperty("Ocp-Apim-Subscription-Key", key)
        c.setRequestProperty("User-Agent", USER_AGENT)
        return c
    }

    private class Transient(message: String, val retryAfter: String?) : IOException(message)

    /** A problem the next recording will not fix; the Say-it block ends on it. */
    class Fatal(message: String) : IOException(message)

    companion object {
        const val ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val PROBE_TIMEOUT_MS = 8_000
        const val ASSESSMENT_LANGUAGE = "en-US"
        const val RECOGNITION_LANGUAGE = "en-GB"
        const val CONTENT_TYPE = "audio/wav; codecs=audio/pcm; samplerate=16000"
        const val STATUS_SUCCESS = "Success"
        private const val USER_AGENT = "minimal-pairs-android"

        fun endpoint(region: String, language: String): String =
            "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=$language&format=detailed"

        /** The `Pronunciation-Assessment` header body before base64: exactly the contract's fields. */
        fun assessmentJson(referenceText: String): String {
            val ref = referenceText.replace("\\", "\\\\").replace("\"", "\\\"")
            return "{\"ReferenceText\":\"$ref\",\"GradingSystem\":\"HundredMark\",\"Granularity\":\"Phoneme\"," +
                "\"Dimension\":\"Comprehensive\",\"EnableMiscue\":false,\"PhonemeAlphabet\":\"IPA\"}"
        }

        /** Base64 of [assessmentJson]. */
        fun assessmentHeader(referenceText: String): String =
            Base64.getEncoder().encodeToString(assessmentJson(referenceText).toByteArray(Charsets.UTF_8))

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parse a `format=detailed` response. `null` when the body is not the
         * expected JSON object (the caller retries). A non-`Success` status
         * yields a [Result] with no scores and no text.
         */
        fun parse(body: String): Result? {
            val root: JsonObject = try {
                lenient.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                return null
            }
            val status = root["RecognitionStatus"]?.jsonPrimitive?.content ?: return null
            val best: JsonObject? = (root["NBest"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }
            if (status != STATUS_SUCCESS || best == null) return Result(status, null, emptyList(), null)
            val acc = best["AccuracyScore"]?.jsonPrimitive?.doubleOrNull
            val word: JsonObject? = (best["Words"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }
            val phonemes = ArrayList<Pair<String, Double>>()
            (word?.get("Phonemes") as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val symbol = o["Phoneme"]?.jsonPrimitive?.content ?: return@forEach
                val score = o["AccuracyScore"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                phonemes.add(symbol to score)
            }
            val lexical = best["Lexical"]?.jsonPrimitive?.content ?: root["DisplayText"]?.jsonPrimitive?.content
            return Result(
                status = status,
                acc = acc ?: word?.get("AccuracyScore")?.jsonPrimitive?.doubleOrNull,
                phonemes = phonemes,
                recognised = ProductionScorer.normaliseRecognised(lexical),
            )
        }
    }
}
