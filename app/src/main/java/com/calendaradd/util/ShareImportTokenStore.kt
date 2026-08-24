package com.calendaradd.util

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * P1: process-scoped holder for the one share-consent token store. Tokens must survive
 * ShareImportActivity's destruction (noHistory, config changes) but never process death —
 * exactly the lifetime in-memory consent can vouch for. ShareImportActivity mints here and
 * MainActivity consumes here, so both ends of the handoff see the same single-use state.
 */
object ShareHandoffTokens {
    /** Intent extra carrying the one-shot handoff token from ShareImportActivity to MainActivity. */
    const val EXTRA_HANDOFF_TOKEN = "com.calendaradd.extra.SHARE_HANDOFF_TOKEN"

    val tokenStore: ShareImportTokenStore = ShareImportTokenStore()

    /**
     * P1/R1: the ACTION_SEND gate in MainActivity. True exactly once per (token, content) pair:
     * false for a missing token, an unknown or replayed token, or a token minted for different
     * content (R3). Callers treat false as an unverified share and re-route it to consent.
     */
    fun consumeHandoff(token: String?, contentKey: String?): Boolean =
        token != null && contentKey != null && tokenStore.consume(token, contentKey)
}

/**
 * P9/R2: one-shot capability tokens gating the share-import consent flow. A token is minted for
 * one specific piece of content (identified by an opaque [contentKey]) and authorizes exactly one
 * [consume] call for that exact content. Every consume attempt burns the token immediately,
 * regardless of outcome, so a double click, a replayed/re-delivered share intent, or an attempt to
 * bind an old token to different content all fail closed rather than allowing a retry. Tokens also
 * expire on their own after [DEFAULT_TTL_MILLIS] even if never consumed.
 */
class ShareImportTokenStore(
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
    }

    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, PendingToken>()

    /** Test-only visibility into map growth; not used by production callers. */
    internal val pendingCount: Int get() = pending.size

    private data class PendingToken(val contentKey: String, val expiresAtMillis: Long)

    /** Mints a fresh single-use token bound to [contentKey]. Pure bookkeeping: no I/O, no enqueue. */
    fun issue(contentKey: String, ttlMillis: Long = DEFAULT_TTL_MILLIS): String {
        // A2: tokens whose activity is destroyed/recreated before consume() or invalidate() is
        // ever called on them would otherwise sit in `pending` forever. Sweeping expired entries
        // on every issue keeps the map bounded by activity within the TTL window rather than by
        // total abandoned previews over the process lifetime.
        sweepExpired()
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        pending[token] = PendingToken(contentKey, nowMillis() + ttlMillis)
        return token
    }

    private fun sweepExpired() {
        val now = nowMillis()
        pending.entries.removeIf { it.value.expiresAtMillis < now }
    }

    /**
     * Consumes [token] iff it was minted for exactly [contentKey] and has not expired. The token
     * is removed atomically on this first call no matter the outcome, so any later call with the
     * same token -- a second click, a stale UI, a replayed intent -- returns false.
     */
    fun consume(token: String, contentKey: String): Boolean {
        val entry = pending.remove(token) ?: return false
        return entry.contentKey == contentKey && nowMillis() <= entry.expiresAtMillis
    }

    /** Discards a token without consuming it, e.g. when a fresh share delivery supersedes it. */
    fun invalidate(token: String) {
        pending.remove(token)
    }
}
