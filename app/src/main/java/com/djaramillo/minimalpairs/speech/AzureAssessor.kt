package com.djaramillo.minimalpairs.speech

import com.djaramillo.minimalpairs.domain.SayItScoring
import com.djaramillo.minimalpairs.domain.model.SayItWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * The one network call the Say-it drill makes: Azure Pronunciation Assessment,
 * scripted against the sentence the coach wrote, with the learner's **own**
 * phone-only Speech resource (docs/CONTRACT.md, "`sayit/scores/`").
 *
 * The key it is given is the one in Settings, which the learner creates as a
 * second, separate free Azure Speech resource. The coach's key runs the
 * coach's pipeline in the cloud and is never on the phone; this class would not
 * know the difference, so the separation is stated where the key is entered and
 * kept true by never sending the key anywhere but
 * `<region>.stt.speech.microsoft.com` in the `Ocp-Apim-Subscription-Key`
 * header. The key never reaches a log, an exception message or the data folder.
 *
 * Everything it knows how to decide lives in [SayItScoring]; this only does
 * HTTP and turns each way of failing into one [SayItScoring.Unscored], because
 * a failure here must always end as *no score*, never as a low one. The
 * learner is watching the screen while it runs, which is what shapes the retry
 * rule below.
 */
class AzureAssessor(private val region: String, private val key: String) {

    /** Azure's answer to one recording: either a body to parse, or a reason there is none. */
    sealed class Answer {
        data class Body(val text: String) : Answer()
        data class Failed(val reason: SayItScoring.Unscored) : Answer()
    }

    /**
     * Assess [wav] (PCM16 mono 16 kHz, as [com.djaramillo.minimalpairs.audio.Wav.forAssessment]
     * produces) against [word]'s sentence. Never throws: every failure comes
     * back as [Answer.Failed].
     *
     * **Only a server fault is retried, and only once.** A rejected key, a
     * throttled resource and a phone with no route to Azure all answer the same
     * way a second later; and the two slow failures — a connect timeout and a
     * read timeout — have already held the learner for ten and twenty-five
     * seconds, so trying again would double a wait that ends in the same "the
     * coach will score this" line. A 5xx is the one case where waiting a beat
     * genuinely helps.
     */
    suspend fun assess(wav: ByteArray, word: SayItWord): Answer = withContext(Dispatchers.IO) {
        var last: Answer.Failed? = null
        for (attempt in 1..ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            when (val answer = once(wav, word.sentence)) {
                is Answer.Body -> return@withContext answer
                is Answer.Failed -> {
                    last = answer
                    if (!retryable(answer.reason) || attempt == ATTEMPTS) return@withContext answer
                    delay(retryDelayMs)
                }
            }
        }
        last ?: Answer.Failed(SayItScoring.Unscored.AZURE_ERROR)
    }

    /**
     * How long to wait before the one retry. Azure's own `Retry-After` wins
     * when it is short enough to be worth holding the screen for; a longer one
     * means the resource wants a rest the learner should not wait through, and
     * [once] has already given up in that case.
     */
    private var retryDelayMs = RETRY_DELAY_MS

    private fun once(wav: ByteArray, sentence: String): Answer {
        retryDelayMs = RETRY_DELAY_MS
        val c = try {
            open(SayItScoring.endpoint(region))
        } catch (e: Exception) {
            return Answer.Failed(SayItScoring.Unscored.OFFLINE)
        }
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", CONTENT_TYPE)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Pronunciation-Assessment", SayItScoring.assessmentHeader(sentence))
            c.setFixedLengthStreamingMode(wav.size)
            val code = try {
                c.outputStream.use { it.write(wav) }
                c.responseCode
            } catch (e: UnknownHostException) {
                return Answer.Failed(SayItScoring.Unscored.OFFLINE)
            } catch (e: InterruptedIOException) {
                // A timeout on a phone that is barely connected: the same thing
                // to the learner as no connection at all, and already a long wait.
                return Answer.Failed(SayItScoring.Unscored.OFFLINE)
            } catch (e: IOException) {
                return Answer.Failed(SayItScoring.Unscored.OFFLINE)
            }
            if (code == HttpURLConnection.HTTP_OK) {
                val body = try {
                    c.inputStream.bufferedReader().use { it.readText() }
                } catch (e: IOException) {
                    return Answer.Failed(SayItScoring.Unscored.AZURE_ERROR)
                }
                return Answer.Body(body)
            }
            // Everything else is "no score", sorted into the three things the
            // learner can act on differently: his key is wrong and he must open
            // Settings, the resource is busy and will come back by itself, or
            // Azure itself is having a moment.
            drainError(c)
            val wait = retryAfterMs(c.getHeaderField("Retry-After"))
            if (wait != null) retryDelayMs = wait
            return Answer.Failed(reasonFor(code, wait))
        } catch (e: Exception) {
            return Answer.Failed(SayItScoring.Unscored.AZURE_ERROR)
        } finally {
            try { c.disconnect() } catch (e: Exception) { /* already gone */ }
        }
    }

    /**
     * Read and discard the error body. An unread error stream keeps the
     * connection out of the pool and the next attempt pays for a new one.
     */
    private fun drainError(c: HttpURLConnection) {
        try { c.errorStream?.use { it.readBytes() } } catch (e: Exception) { /* nothing to learn from it */ }
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

    companion object {
        /** One retry, and only for a 5xx: the learner is looking at the screen. */
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_200L

        /** Longer than this and the wait belongs to the coach, not to the learner. */
        const val MAX_RETRY_AFTER_MS = 5_000L

        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 25_000

        /** Exactly what [com.djaramillo.minimalpairs.audio.Wav.forAssessment] produces. */
        const val CONTENT_TYPE = "audio/wav; codecs=audio/pcm; samplerate=16000"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private const val USER_AGENT = "minimal-pairs-android"

        /**
         * What a non-200 status means to the learner. Pure, so the whole table
         * is unit tested without a socket.
         *
         * [waitMs] is what [retryAfterMs] made of `Retry-After`: null means
         * Azure asked for longer than the learner will stand at the screen, and
         * a server fault then stops being something to retry and becomes one
         * more "the coach will score this".
         *
         * Only [SayItScoring.Unscored.AZURE_ERROR] is retried, so this table is
         * also the retry rule: a rejected key and a throttled resource answer
         * the same way a second later and are never asked twice.
         */
        fun reasonFor(code: Int, waitMs: Long? = RETRY_DELAY_MS): SayItScoring.Unscored = when {
            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN || code == HTTP_NOT_FOUND ->
                SayItScoring.Unscored.KEY_REJECTED
            code == HTTP_TOO_MANY_REQUESTS -> SayItScoring.Unscored.THROTTLED
            code >= 500 -> if (waitMs == null) SayItScoring.Unscored.THROTTLED else SayItScoring.Unscored.AZURE_ERROR
            else -> SayItScoring.Unscored.AZURE_ERROR
        }

        /**
         * `Retry-After` in milliseconds when it is short enough to wait for, the
         * default when the header is absent or unreadable, and null when Azure
         * asks for longer than [MAX_RETRY_AFTER_MS] — a wait that belongs to the
         * coach, not to somebody holding a phone.
         */
        fun retryAfterMs(header: String?): Long? {
            val seconds = header?.trim()?.toLongOrNull() ?: return RETRY_DELAY_MS
            if (seconds < 0) return RETRY_DELAY_MS
            val ms = seconds * 1000
            return if (ms > MAX_RETRY_AFTER_MS) null else ms.coerceAtLeast(RETRY_DELAY_MS)
        }

        /** Whether [assess] will ask again after this outcome. */
        fun retryable(reason: SayItScoring.Unscored): Boolean = reason == SayItScoring.Unscored.AZURE_ERROR
    }
}
