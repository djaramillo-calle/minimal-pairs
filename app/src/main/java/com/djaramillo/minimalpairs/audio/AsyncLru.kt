package com.djaramillo.minimalpairs.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure-Kotlin core of [ClipCache]: a small LRU of asynchronously loaded values
 * with deduplicated in-flight loads. Kept free of Android types so the
 * bookkeeping (dedupe, eviction, failure, release) is unit-tested on the JVM.
 *
 * A failed load is forgotten immediately, so the next [prefetch] of the same
 * key loads again instead of replaying the old exception; that is what makes
 * "Retry" on a bad clip meaningful.
 */
internal class AsyncLru<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val capacity: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val release: (V) -> Unit,
    private val load: suspend (K) -> V,
) {
    private val lock = Any()
    private val loaded = LinkedHashMap<K, V>(16, 0.75f, true)
    private val inFlight = HashMap<K, Deferred<V>>()
    private var closed = false

    /** Number of loaded values currently held (for tests / diagnostics). */
    val size: Int get() = synchronized(lock) { loaded.size }

    /** Start loading (idempotent). The returned deferred completes with the value or fails with the load's exception. */
    fun prefetch(key: K): Deferred<V> {
        synchronized(lock) {
            loaded[key]?.let { hit -> return CompletableDeferred(hit) }
            inFlight[key]?.let { return it }
            if (closed) return CompletableDeferred<V>().apply { cancel(CancellationException("cache closed")) }
            val d = scope.async(dispatcher) {
                // For `async` the coroutine's Job is the Deferred itself, so identity
                // against inFlight[key] tells whether this load is still the wanted one.
                val me = currentCoroutineContext()[Job]
                val value = try {
                    load(key)
                } catch (e: Throwable) {
                    synchronized(lock) { if (inFlight[key] === me) inFlight.remove(key) }
                    throw e
                }
                val kept = synchronized(lock) {
                    if (closed || inFlight[key] !== me) false
                    else {
                        inFlight.remove(key)
                        loaded[key] = value
                        evictLocked()
                        true
                    }
                }
                if (!kept) {
                    release(value)
                    throw CancellationException("cache released")
                }
                value
            }
            inFlight[key] = d
            return d
        }
    }

    /** Get the value, loading it now if necessary. Throws when the load fails. */
    suspend fun get(key: K): V {
        val v = prefetch(key).await()
        synchronized(lock) { loaded[key] } // touch for LRU order
        return v
    }

    /** Forget a loaded value without releasing it (the caller knows it is already gone). */
    fun invalidate(key: K) {
        synchronized(lock) { loaded.remove(key) }
    }

    private fun evictLocked() {
        while (loaded.size > capacity) {
            val eldest = loaded.entries.iterator().next()
            loaded.remove(eldest.key)
            release(eldest.value)
        }
    }

    /** Release every value, cancel in-flight loads and refuse further work. */
    fun releaseAll() {
        val values = synchronized(lock) {
            closed = true
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
            val all = loaded.values.toList()
            loaded.clear()
            all
        }
        values.forEach { release(it) }
    }
}
