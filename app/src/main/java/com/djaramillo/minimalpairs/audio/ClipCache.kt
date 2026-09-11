package com.djaramillo.minimalpairs.audio

import com.djaramillo.minimalpairs.clips.ClipPack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Small LRU of decoded, track-bound clips keyed by (word, voice).
 *
 * Latency design (docs/DESIGN.md "Audio latency budget", 150 ms tap → sound):
 * the scheduler draws one trial at a time and its next draw depends on the
 * answer, so we cannot decode "the session" up front. Instead trial `i+1` is
 * planned the moment trial `i` is answered and both its clips (target and
 * foil, same voice, so the tap-to-hear-both feedback is instant too) are
 * decoded during the feedback pause; trial 1 is decoded during the Home →
 * Trial transition behind a small loading indicator. Decoding a 4 KB Opus
 * clip takes ~10–30 ms; writing it into a static AudioTrack a few ms more.
 * Auto-play at trial start waits for the clip to be ready; a replay is a
 * `play()` on the already-loaded track. The cache holds at most [capacity]
 * tracks so AudioFlinger's per-app track limit is never approached.
 */
class ClipCache(
    private val pack: ClipPack,
    private val player: Player,
    private val scope: CoroutineScope,
    private val capacity: Int = 8,
) {
    data class Key(val word: String, val voice: String)

    private val lock = Any()
    private val loaded = LinkedHashMap<Key, Player.Loaded>(16, 0.75f, true)
    private val inFlight = HashMap<Key, Deferred<Player.Loaded>>()
    private var closed = false

    /** Start decoding (idempotent). The returned deferred completes with the track-bound clip. */
    fun prefetch(word: String, voice: String): Deferred<Player.Loaded> {
        val key = Key(word, voice)
        synchronized(lock) {
            loaded[key]?.let { hit -> return CompletableDeferred(hit) }
            inFlight[key]?.let { return it }
            if (closed) return CompletableDeferred<Player.Loaded>().apply { cancel(CancellationException("cache closed")) }
            val d = scope.async(Dispatchers.Default) {
                val clip = pack.open(word, voice).use { ClipDecoder.decode(it) }
                val bound = player.prepare(clip)
                val me = currentCoroutineContext()[Job]
                val kept = synchronized(lock) {
                    if (closed || inFlight[key] !== me) false
                    else {
                        inFlight.remove(key)
                        loaded[key] = bound
                        evictLocked()
                        true
                    }
                }
                if (!kept) {
                    player.release(bound)
                    throw CancellationException("clip cache released")
                }
                bound
            }
            inFlight[key] = d
            return d
        }
    }

    /** Get the clip, decoding it now if necessary. Throws when the clip cannot be opened or decoded. */
    suspend fun get(word: String, voice: String): Player.Loaded {
        val v = prefetch(word, voice).await()
        if (v.released) {
            // Evicted between decode and use (very unlikely): decode again.
            synchronized(lock) { loaded.remove(Key(word, voice)) }
            return prefetch(word, voice).await()
        }
        synchronized(lock) { loaded[Key(word, voice)] } // touch for LRU order
        return v
    }

    private fun evictLocked() {
        while (loaded.size > capacity) {
            val eldest = loaded.entries.iterator().next()
            loaded.remove(eldest.key)
            player.release(eldest.value)
        }
    }

    /** Stop and release every track and refuse further work. Call when the session ends or is abandoned. */
    fun releaseAll() {
        val tracks = synchronized(lock) {
            closed = true
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
            val all = loaded.values.toList()
            loaded.clear()
            all
        }
        tracks.forEach { player.release(it) }
    }
}
