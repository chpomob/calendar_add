package com.calendaradd

import com.calendaradd.util.ShareContentKeys
import com.calendaradd.util.ShareHandoffTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1/R1: [MainActivity] is exported, so any app can address an ACTION_SEND straight at it and
 * bypass ShareImportActivity's consent screen. The gate itself (consumeShareHandoff plus the
 * re-route startActivity) needs an instrumented environment; these JVM tests pin the units that
 * decide it: the handoff consume (missing / forged / valid-once / replayed / mismatched token)
 * and the canonical content-key computation both ends must agree on.
 */
class MainActivityShareHandoffTest {

    @Test
    fun `share without a token is rejected and must be re-routed to consent`() {
        // AC1: an ACTION_SEND reaching MainActivity without the handoff extra fails the gate.
        val contentKey = ShareContentKeys.forText("no token carried")
        assertFalse(ShareHandoffTokens.consumeHandoff(null, contentKey))
        assertFalse(ShareHandoffTokens.consumeHandoff("", contentKey))
    }

    @Test
    fun `forged or unknown token is rejected`() {
        assertFalse(ShareHandoffTokens.consumeHandoff("attacker-forged-token", ShareContentKeys.forText("forged")))
        assertFalse(ShareHandoffTokens.consumeHandoff("deadbeef", ShareContentKeys.forText("guessed")))
    }

    @Test
    fun `valid token authorizes the import exactly once`() {
        // AC3 then AC2: the first consume (the import) succeeds; a replay of the same token fails.
        val contentKey = ShareContentKeys.forText("standup 9:30 tomorrow")
        val token = ShareHandoffTokens.tokenStore.issue(contentKey)

        assertTrue(ShareHandoffTokens.consumeHandoff(token, contentKey))
        assertFalse(ShareHandoffTokens.consumeHandoff(token, contentKey))
    }

    @Test
    fun `a re-consented share gets a fresh distinct token while the old one stays dead`() {
        val contentKey = ShareContentKeys.forText("reconsent")
        val first = ShareHandoffTokens.tokenStore.issue(contentKey)
        assertTrue(ShareHandoffTokens.consumeHandoff(first, contentKey))

        val second = ShareHandoffTokens.tokenStore.issue(contentKey)
        assertNotEquals(first, second)
        assertFalse(ShareHandoffTokens.consumeHandoff(first, contentKey))
        assertTrue(ShareHandoffTokens.consumeHandoff(second, contentKey))
    }

    @Test
    fun `token minted for one content cannot authorize another`() {
        // R3: keys are SHA-256-bound, so different text never shares a key. The failed attempt
        // burns the token, so the originally-correct content cannot redeem it either.
        val token = ShareHandoffTokens.tokenStore.issue(ShareContentKeys.forText("event a"))

        assertFalse(ShareHandoffTokens.consumeHandoff(token, ShareContentKeys.forText("event b")))
        assertFalse(ShareHandoffTokens.consumeHandoff(token, ShareContentKeys.forText("event a")))
    }

    @Test
    fun `text content key is the pinned sha256 of the shared text`() {
        // Pins the exact key format ("text:" + lowercase hex SHA-256) so MainActivity and
        // ShareImportActivity — both computing via ShareContentKeys — can never drift apart.
        assertEquals(
            "text:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ShareContentKeys.forText("hello")
        )
    }

    @Test
    fun `same text maps to the same key and different text to different keys`() {
        assertEquals(ShareContentKeys.forText("a"), ShareContentKeys.forText("a"))
        assertNotEquals(ShareContentKeys.forText("a"), ShareContentKeys.forText("a "))
    }
}
