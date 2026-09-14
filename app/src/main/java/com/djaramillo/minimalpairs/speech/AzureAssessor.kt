package com.djaramillo.minimalpairs.speech

import com.djaramillo.minimalpairs.clips.AzureTts
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
 * a failure here must always end as *no score*, never as a low one. The learner
 * is waiting for the answer, so it retries a transient failure once and then
 * gives up rather than holding the screen.
 */
class AzureAssessor(private val region: String, private val key: String) {

    /** Azure's answer to one recording: either a body to parse, or a reason there is none. */
    sealed class Answer {
        data class Body(val text: String) : Answer()
        data class Failed(val reason: SayItScoring.Unscored, val detail: String? = null) : Answer()
    }

    /**
     * Assess [wav] (PCM16 mono 16 kHz, as [com.djaramillo.minimalpairs.audio.Wav.forAssessment]
     * produces) against [word]'s sentence. Never throws: every failure comes
     * back as [Answer.Failed].
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
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        last ?: Answer.Failed(SayItScoring.Unscored.AZURE_ERROR)
    }

    /**
     * A transient failure is worth exactly one more try. A rejected key, a
     * throttled resource or a phone with no route to Azure will answer the same
     * way a second later, and the learner would only wait longer for the same
     * "the coach will score this" line.
     */
    private fun retryable(reason: SayItScoring.Unscored): Boolean =
        reason == SayItScoring.Unscored.AZURE_ERROR

    private fun once(wav: ByteArray, sentence: String): Answer {
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
                // to the learner as no connection at all.
                return Answer.Failed(SayItScoring.Unscored.OFFLINE)
            } catch (e: IOException) {
                return Answer.Failed(SayItScoring.Unscored.OFFLINE)
            }
            if (code == HttpURLConnection.HTTP_OK) {
                val body = try {
                    c.inputStream.bufferedReader().use { it.readText() }
                } catch (e: IOException) {
                    return Answer.Failed(SayItScoring.Unscored.AZURE_ERROR, AzureTts.describe(code))
                }
                return Answer.Body(body)
            }
            // Everything else is "no score", with one plain sentence for the
            // screen. 401/403 is the wrong key, 429 the free tier's rate limit,
            // 5xx Azure itself; none of them is a number.
            return Answer.Failed(SayItScoring.Unscored.AZURE_ERROR, AzureTts.describe(code))
        } catch (e: Exception) {
            return Answer.Failed(SayItScoring.Unscored.AZURE_ERROR)
        } finally {
            try { c.disconnect() } catch (e: Exception) { /* already gone */ }
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

    companion object {
        /** One retry, no more: the learner is looking at the screen. */
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_200L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 25_000

        /** Exactly what [com.djaramillo.minimalpairs.audio.Wav.forAssessment] produces. */
        const val CONTENT_TYPE = "audio/wav; codecs=audio/pcm; samplerate=16000"

        private const val USER_AGENT = "minimal-pairs-android"
    }
}
