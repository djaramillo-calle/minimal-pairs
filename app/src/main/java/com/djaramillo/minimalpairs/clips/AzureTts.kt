package com.djaramillo.minimalpairs.clips

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * The two Azure Speech REST calls the app makes with the user's own key:
 * the voice list (to test the key) and single-word synthesis. The key is
 * only ever put in the `Ocp-Apim-Subscription-Key` header; it is never
 * logged and never part of an exception message.
 */
class AzureTts(private val region: String, private val key: String) {

    /** Names of the `en-GB` neural voices the subscription can use. */
    suspend fun listBritishVoices(): List<String> {
        val c = open("https://$region.tts.speech.microsoft.com/cognitiveservices/voices/list", "GET")
        try {
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException(describe(code))
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return parseVoices(body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Render one word; retries transient failures (429, 5xx, network) up to
     * [ATTEMPTS] times. Throws [Fatal] for problems that will not go away by
     * trying the next word (bad key, bad region, unreachable host, a very long
     * Retry-After): the renderer stops the run on those.
     */
    suspend fun synthesize(voice: String, word: String): ByteArray {
        var last: IOException? = null
        for (attempt in 1..ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                return synthesizeOnce(voice, word)
            } catch (e: Transient) {
                last = e
                if (attempt < ATTEMPTS) delay(RenderPlan.retryDelayMs(attempt, e.retryAfter))
            }
        }
        throw last ?: IOException("synthesis failed")
    }

    private fun synthesizeOnce(voice: String, word: String): ByteArray {
        val c = open("https://$region.tts.speech.microsoft.com/cognitiveservices/v1", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/ssml+xml")
        c.setRequestProperty("X-Microsoft-OutputFormat", RenderPlan.FORMAT)
        try {
            val code = try {
                c.outputStream.use { it.write(RenderPlan.ssml(voice, word).toByteArray(Charsets.UTF_8)) }
                c.responseCode
            } catch (e: UnknownHostException) {
                throw Fatal("cannot reach $region.tts.speech.microsoft.com: check the region and the connection")
            } catch (e: IOException) {
                throw Transient("network: ${e.javaClass.simpleName}", null)
            }
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val data = try { c.inputStream.use { it.readBytes() } } catch (e: IOException) {
                        throw Transient("network: ${e.javaClass.simpleName}", null)
                    }
                    RenderPlan.validateClip(data)?.let { throw Transient("bad clip for '$word' ($voice): $it", null) }
                    return data
                }
                code == 429 || code >= 500 -> {
                    val ra = c.getHeaderField("Retry-After")
                    val secs = ra?.trim()?.toLongOrNull()
                    if (secs != null && secs > MAX_RETRY_AFTER_S) {
                        throw Fatal("${describe(code)}; Azure asks to wait $secs s (quota exhausted?). Try again later")
                    }
                    throw Transient(describe(code), ra)
                }
                code == 401 || code == 403 || code == 404 -> throw Fatal(describe(code))
                else -> throw IOException(describe(code))
            }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Ocp-Apim-Subscription-Key", key)
        c.setRequestProperty("User-Agent", "minimal-pairs-android")
        return c
    }

    private class Transient(message: String, val retryAfter: String?) : IOException(message)

    /** A problem the next word will not fix; the renderer stops on it. */
    class Fatal(message: String) : IOException(message)

    companion object {
        const val ATTEMPTS = 3
        const val MAX_RETRY_AFTER_S = 120L

        /** Plain-language meaning of the Azure status codes a learner may hit. */
        fun describe(code: Int): String = when (code) {
            401 -> "Azure rejected the key (401): check the key and the region"
            403 -> "Azure refused the request (403): the key is not allowed to use this service or region"
            404 -> "Azure endpoint not found (404): check the region"
            429 -> "Azure is rate-limiting (429)"
            in 500..599 -> "Azure server error ($code)"
            else -> "Azure returned HTTP $code"
        }

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseVoices(listingJson: String): List<String> {
            val arr = try { lenient.parseToJsonElement(listingJson).jsonArray } catch (e: Exception) { return emptyList() }
            return arr.mapNotNull { el ->
                val o = try { el.jsonObject } catch (e: Exception) { return@mapNotNull null }
                val locale = o["Locale"]?.jsonPrimitive?.content
                val name = o["ShortName"]?.jsonPrimitive?.content
                if (locale == "en-GB" && !name.isNullOrBlank()) name else null
            }
        }
    }
}
