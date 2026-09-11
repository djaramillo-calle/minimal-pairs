package com.djaramillo.minimalpairs.audio

import com.djaramillo.minimalpairs.clips.ClipPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

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
 *
 * A clip that fails to open or decode is not remembered: the next
 * [prefetch] of it decodes again (see [AsyncLru]), so the Trial screen's
 * Retry can succeed after a transient failure.
 */
class ClipCache(
    pack: ClipPack,
    player: Player,
    scope: CoroutineScope,
    capacity: Int = 8,
) {
    data class Key(val word: String, val voice: String)

    private val lru = AsyncLru<Key, Player.Loaded>(scope, capacity, release = player::release) { key ->
        val clip = pack.open(key.word, key.voice).use { ClipDecoder.decode(it) }
        player.prepare(clip)
    }

    /** Start decoding (idempotent). The returned deferred completes with the track-bound clip. */
    fun prefetch(word: String, voice: String): Deferred<Player.Loaded> = lru.prefetch(Key(word, voice))

    /** Get the clip, decoding it now if necessary. Throws when the clip cannot be opened or decoded. */
    suspend fun get(word: String, voice: String): Player.Loaded {
        val key = Key(word, voice)
        val v = lru.get(key)
        if (v.released) {
            // Evicted between decode and use (very unlikely): decode again.
            lru.invalidate(key)
            return lru.get(key)
        }
        return v
    }

    /** Stop and release every track and refuse further work. Call when the session ends or is abandoned. */
    fun releaseAll() = lru.releaseAll()
}
