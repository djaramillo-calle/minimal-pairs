package com.djaramillo.minimalpairs.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AsyncLruTest {
    // Like viewModelScope: a SupervisorJob so one failed async does not cancel the scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val released = mutableListOf<String>()

    @After
    fun tearDown() = scope.cancel()

    private fun lru(capacity: Int = 8, load: suspend (String) -> String) =
        AsyncLru<String, String>(scope, capacity, release = { synchronized(released) { released += it } }, load = load)

    @Test
    fun failed_load_is_forgotten_so_a_retry_loads_again() = runBlocking {
        val calls = AtomicInteger()
        val cache = lru { key ->
            if (calls.incrementAndGet() == 1) throw IOException("corrupt $key")
            "pcm:$key"
        }
        try {
            cache.get("a")
            fail("first load should fail")
        } catch (e: IOException) {
            assertEquals("corrupt a", e.message)
        }
        assertEquals("pcm:a", cache.get("a"))
        assertEquals(2, calls.get())
        assertEquals(1, cache.size)
    }

    @Test
    fun successful_load_is_cached_and_deduplicated() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = lru { key -> calls.incrementAndGet(); gate.await(); "pcm:$key" }
        val d1 = cache.prefetch("a")
        val d2 = cache.prefetch("a")
        assertSame(d1, d2)
        gate.complete(Unit)
        assertEquals("pcm:a", d1.await())
        assertEquals("pcm:a", cache.get("a"))
        assertEquals("pcm:a", cache.prefetch("a").await())
        assertEquals(1, calls.get())
    }

    @Test
    fun eviction_releases_the_least_recently_used_value() = runBlocking {
        val cache = lru(capacity = 2) { key -> "pcm:$key" }
        cache.get("a")
        cache.get("b")
        cache.get("a") // touch: b is now the eldest
        cache.get("c")
        assertEquals(listOf("pcm:b"), released)
        assertEquals(2, cache.size)
    }

    @Test
    fun release_all_releases_values_and_refuses_further_loads() = runBlocking {
        val cache = lru { key -> "pcm:$key" }
        cache.get("a")
        cache.get("b")
        cache.releaseAll()
        assertEquals(setOf("pcm:a", "pcm:b"), released.toSet())
        assertEquals(0, cache.size)
        val d = cache.prefetch("c")
        assertTrue(d.isCancelled)
    }

    @Test
    fun value_loaded_after_release_all_is_released_not_kept() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val cache = lru { key -> gate.await(); "pcm:$key" }
        val d = cache.prefetch("a")
        cache.releaseAll()
        gate.complete(Unit)
        assertTrue(d.isCancelled)
        assertEquals(0, cache.size)
    }

    @Test
    fun invalidate_forgets_without_releasing() = runBlocking {
        val calls = AtomicInteger()
        val cache = lru { key -> calls.incrementAndGet(); "pcm:$key" }
        cache.get("a")
        cache.invalidate("a")
        assertEquals(0, cache.size)
        assertTrue(released.isEmpty())
        cache.get("a")
        assertEquals(2, calls.get())
    }
}
