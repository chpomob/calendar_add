package com.calendaradd.service

import android.net.Uri
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.AppLog
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

interface WebSearchClient {
    suspend fun findFirstResultUrl(query: String): String?
}

class PreferencesWebSearchClient(
    private val preferencesManager: PreferencesManager,
    private val duckDuckGoClient: WebSearchClient = DuckDuckGoWebSearchClient()
) : WebSearchClient {
    override suspend fun findFirstResultUrl(query: String): String? {
        val provider = preferencesManager.webSearchProvider
        if (provider == "brave") {
            val apiKey = preferencesManager.braveSearchApiKey
            if (apiKey.isNotBlank()) {
                BraveSearchApiClient(apiKey).findFirstResultUrl(query)?.let { return it }
            }
        }
        return duckDuckGoClient.findFirstResultUrl(query)
    }
}

class BraveSearchApiClient(
    private val apiKey: String
) : WebSearchClient {
    override suspend fun findFirstResultUrl(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val searchUrl = "https://api.search.brave.com/res/v1/web/search?q=${Uri.encode(query)}&count=8"
            val response = Jsoup.connect(searchUrl)
                .timeout(10000)
                .ignoreContentType(true)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .execute()
            if (response.statusCode() !in 200..299) return@withContext null
            selectBestResultUrl(query, response.body())
        } catch (e: Exception) {
            AppLog.w("WebVerificationService", "Brave web lookup failed: ${e.message}")
            null
        }
    }

    internal fun selectBestResultUrl(query: String, responseBody: String): String? {
        val root = JsonParser.parseString(responseBody).asJsonObject
        val results = root.getAsJsonObject("web")
            ?.getAsJsonArray("results")
            ?: return null
        return results.mapIndexedNotNull { index, element ->
            val result = element as? JsonObject ?: return@mapIndexedNotNull null
            val url = result.get("url")?.asString?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            SearchResultCandidate(
                url = url,
                title = result.get("title")?.asString.orEmpty(),
                snippet = result.get("description")?.asString.orEmpty(),
                rank = index
            )
        }.maxByOrNull { scoreSearchResult(query, it) }?.url
    }
}

class DuckDuckGoWebSearchClient : WebSearchClient {
    companion object {
        private const val MAX_RANKED_RESULTS = 8
    }

    override suspend fun findFirstResultUrl(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val searchUrl = "https://html.duckduckgo.com/html/?q=${Uri.encode(query)}"
            val document = Jsoup.connect(searchUrl)
                .timeout(10000)
                .userAgent("Mozilla/5.0")
                .get()
            val candidates = document.select("div.result")
                .mapIndexedNotNull { index, element ->
                    val link = element.selectFirst("a.result__a") ?: return@mapIndexedNotNull null
                    val url = resolveSearchResultUrl(link.attr("href")) ?: return@mapIndexedNotNull null
                    SearchResultCandidate(
                        url = url,
                        title = link.text(),
                        snippet = element.selectFirst(".result__snippet")?.text().orEmpty(),
                        rank = index
                    )
                }
                .take(MAX_RANKED_RESULTS)
                .ifEmpty {
                    document.select("a[href*='uddg=']")
                        .mapIndexedNotNull { index, link ->
                            val url = resolveSearchResultUrl(link.attr("href")) ?: return@mapIndexedNotNull null
                            SearchResultCandidate(url = url, title = link.text(), snippet = "", rank = index)
                        }
                        .take(MAX_RANKED_RESULTS)
                }
            candidates.maxByOrNull { scoreSearchResult(query, it) }?.url
        } catch (e: Exception) {
            AppLog.w("WebVerificationService", "Public web lookup failed: ${e.message}")
            null
        }
    }

    private fun resolveSearchResultUrl(href: String?): String? {
        if (href.isNullOrBlank()) return null
        if (href.startsWith("http")) return href
        val redirectMatch = Regex("[?&]uddg=([^&]+)").find(href)
        if (redirectMatch != null) {
            return Uri.decode(redirectMatch.groupValues[1])
        }
        return if (href.startsWith("//")) "https:$href" else "https://duckduckgo.com$href"
    }
}

private data class SearchResultCandidate(
    val url: String,
    val title: String,
    val snippet: String,
    val rank: Int
)

private fun scoreSearchResult(query: String, candidate: SearchResultCandidate): Int {
    val queryTokens = query.normalizeSearchTokens()
        .filterNot { it in SEARCH_STOP_WORDS }
        .distinct()
    val title = candidate.title.normalizeComparable()
    val haystack = "${candidate.title} ${candidate.snippet} ${candidate.url}".normalizeComparable()
    val tokenScore = queryTokens.sumOf { token ->
        when {
            title.contains(token) -> 4
            haystack.contains(token) -> 2
            else -> 0
        }
    }
    val phraseBonus = query.extractLikelyTitlePhrase()?.let { phrase ->
        if (title.contains(phrase.normalizeComparable())) 12 else 0
    } ?: 0
    val eventPageBonus = if (candidate.url.contains(Regex("(?i)/(event|events|agenda|programmation)/"))) 4 else 0
    val pdfPenalty = if (candidate.url.contains(Regex("(?i)\\.pdf($|[?#])"))) 6 else 0
    val rankPenalty = candidate.rank
    return tokenScore + phraseBonus + eventPageBonus - pdfPenalty - rankPenalty
}

internal val SEARCH_STOP_WORDS = setOf(
    "the",
    "and",
    "for",
    "with",
    "from",
    "this",
    "that",
    "event",
    "flyer",
    "poster",
    "schedule",
    "series",
    "les",
    "des",
    "une",
    "pour",
    "avec",
    "dans",
    "sur",
    "par",
    "aux",
    "est",
    "sont",
    "soirée",
    "concert"
)

internal fun String.normalizeSearchTokens(): List<String> {
    return lowercase()
        .replace(Regex("[^a-z0-9à-öø-ÿ]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 3 }
}

private fun String.normalizeComparable(): String {
    return lowercase().replace(Regex("[^a-z0-9à-öø-ÿ]+"), " ")
}

private fun String.extractLikelyTitlePhrase(): String? {
    return split(Regex("\\s+"))
        .takeWhile { token -> !token.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        .joinToString(" ")
        .trim()
        .takeIf { it.length >= 6 }
}
