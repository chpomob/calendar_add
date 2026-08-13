package com.calendaradd

import com.calendaradd.util.ShareImportTokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P9/R2: [ShareImportActivity]'s consent gate cannot be exercised as a plain JVM test (it needs a
 * real Activity/Intent/ContentResolver), so these tests target [ShareImportTokenStore], the unit
 * that actually enforces one-shot-token semantics: exactly one enqueue per token, no replay, no
 * binding a token to different content, and expiry.
 */
class ShareImportActivityTest {

    @Test
    fun `token authorizes exactly one consume for its content`() {
        val store = ShareImportTokenStore()
        val token = store.issue("content-a")

        assertTrue(store.consume(token, "content-a"))
        // AC2: a second click / replay of the same token must fail even though it was valid a
        // moment ago -- exactly one enqueue per token.
        assertFalse(store.consume(token, "content-a"))
    }

    @Test
    fun `token never authorizes a different content key`() {
        val store = ShareImportTokenStore()
        val token = store.issue("content-a")

        assertFalse(store.consume(token, "content-b"))
    }

    @Test
    fun `a rejected consume attempt still burns the token`() {
        val store = ShareImportTokenStore()
        val token = store.issue("content-a")

        // Mismatched content -- rejected.
        assertFalse(store.consume(token, "content-b"))
        // The token was burned on that first attempt, so even the originally-correct content key
        // can no longer redeem it. Fail-closed: no retries against a burned token.
        assertFalse(store.consume(token, "content-a"))
    }

    @Test
    fun `unknown token is rejected`() {
        val store = ShareImportTokenStore()
        store.issue("content-a")

        assertFalse(store.consume("some-other-token-never-issued", "content-a"))
    }

    @Test
    fun `expired token is rejected even with the correct content key`() {
        var now = 1_000_000L
        val store = ShareImportTokenStore(nowMillis = { now })
        val token = store.issue("content-a", ttlMillis = 5_000L)

        now += 5_001L

        assertFalse(store.consume(token, "content-a"))
    }

    @Test
    fun `token is still valid at the exact expiry boundary`() {
        var now = 0L
        val store = ShareImportTokenStore(nowMillis = { now })
        val token = store.issue("content-a", ttlMillis = 5_000L)

        now = 5_000L

        assertTrue(store.consume(token, "content-a"))
    }

    @Test
    fun `default expiry is five minutes`() {
        assertEquals(5 * 60 * 1000L, ShareImportTokenStore.DEFAULT_TTL_MILLIS)
    }

    @Test
    fun `each issued token is unique`() {
        val store = ShareImportTokenStore()
        val tokens = (1..50).map { store.issue("content-$it") }

        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test
    fun `re-delivered content mints a fresh token that supersedes an unconsumed one`() {
        val store = ShareImportTokenStore()
        val firstToken = store.issue("content-a")

        // Simulates onNewIntent: the pending token from the first delivery is dropped before a
        // fresh preview and token are issued for the re-delivered intent.
        store.invalidate(firstToken)
        val secondToken = store.issue("content-a")

        assertNotEquals(firstToken, secondToken)
        assertFalse(store.consume(firstToken, "content-a"))
        assertTrue(store.consume(secondToken, "content-a"))
    }

    @Test
    fun `invalidate on an unknown token is a harmless no-op`() {
        val store = ShareImportTokenStore()

        store.invalidate("never-issued")
    }

    @Test
    fun `denial leaves the token unconsumable`() {
        val store = ShareImportTokenStore()
        val token = store.issue("content-a")

        // Simulates a dismiss/cancel: the pending token is invalidated without ever consuming it.
        store.invalidate(token)

        assertFalse(store.consume(token, "content-a"))
    }

    @Test
    fun `expired tokens abandoned without consume or invalidate are swept, not retained forever`() {
        var now = 0L
        val store = ShareImportTokenStore(nowMillis = { now })

        // Simulates repeated abandoned previews (e.g. the noHistory activity destroyed before the
        // user acts): each token expires and is never consumed or explicitly invalidated.
        repeat(10) {
            store.issue("content-abandoned", ttlMillis = 1_000L)
            now += 2_000L
        }

        // The next issue should sweep the now-expired backlog rather than let it accumulate.
        store.issue("content-fresh", ttlMillis = 1_000L)

        assertTrue(store.pendingCount <= 1)
    }
}
