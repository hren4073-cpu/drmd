package com.drmd.lj2pdf

import android.net.Uri
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A platform profile: knows how to ENUMERATE the article/post URLs of a site
 * and how to give each a stable id. The render → PDF → merge+TOC engine is
 * shared; only enumeration differs per platform.
 *
 * LiveJournal keeps its dedicated built-in path in ConvertService (Profiles
 * returns null for it). Everything else is handled here, so adding a new site
 * = add a SiteProfile and register it.
 */
interface SiteProfile {
    val key: String

    /** Filesystem-safe, stable id for a post URL (used for posts/<id>.pdf). */
    fun idOf(url: String): String

    /** Enumerate all article URLs (newest-first where possible). */
    suspend fun scanAll(
        r: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        note: (String) -> Unit
    ): List<String>

    /** Enumerate only URLs not already archived (fast incremental update). */
    suspend fun scanNew(
        r: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        knownIds: Set<String>, note: (String) -> Unit
    ): List<String>
}

private const val P_TIMEOUT_MS = 90_000L
private const val P_SETTLE_MS = 1200L

object Profiles {
    /** Returns the profile for a URL, or null when the built-in LJ path applies. */
    fun forBase(base: String): SiteProfile? {
        val host = (Uri.parse(base).host ?: "").lowercase()
        return when {
            host.contains("livejournal.com") -> null          // built-in LJ engine
            host.contains("habr.com") || host.contains("habrahabr") -> HabrProfile
            // TODO osnova: dtf.ru / tjournal.ru share one engine — enumerate via
            //   their API (api.dtf.ru / api.tjournal) instead of the generic crawl.
            // TODO sefaria.org: structure + bilingual text via api.sefaria.org;
            //   lay a translator hook here (he/aramaic → en) for RAG.
            else -> GenericProfile
        }
    }
}

/**
 * Generic crawler: collects same-host links from the given page (e.g. a table
 * of contents like Sefaria). Good default / fallback; refine per-site later.
 */
object GenericProfile : SiteProfile {
    override val key = "generic"

    override fun idOf(url: String): String {
        val u = url.substringBefore('#').substringBefore('?')
        return "g" + Integer.toHexString(u.hashCode() and 0x7fffffff)
    }

    private val pageNumRe = Regex("(?:[?&]page=|[?&]p=|/page/)(\\d+)")

    override suspend fun scanAll(
        r: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        note: (String) -> Unit
    ): List<String> {
        // Auto structure detection: collect content links from the page, then
        // FOLLOW pagination (?page=N, ?p=N, /page/N) to the next page until it
        // runs out — covers forum threads / index pages and paginated TOCs.
        val baseTrim = base.trimEnd('/')
        val items = LinkedHashSet<String>()
        val seenPages = HashSet<String>()
        var pageUrl: String? = base
        var visited = 0
        while (pageUrl != null && visited < 300 && items.size < max && !ConvertBus.cancelRequested) {
            if (!seenPages.add(pageUrl)) break
            visited++
            note("Scanning page $visited (${items.size} items)…")
            val all = withTimeoutOrNull(P_TIMEOUT_MS) { r.collectAllLinks(pageUrl!!, P_SETTLE_MS) }
                ?: emptyList()
            all.filter { isContent(it, baseTrim) }.forEach { items.add(it) }
            ConvertBus.scanProgress(visited, items.size)
            pageUrl = nextPage(all, pageUrl!!)
        }
        val list = if (items.isEmpty()) listOf(base) else items.toList().take(max)
        ConvertBus.log("[generic] ${list.size} item(s) over $visited page(s)")
        return list
    }

    override suspend fun scanNew(
        r: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        knownIds: Set<String>, note: (String) -> Unit
    ): List<String> = scanAll(r, base, step, from, max, note).filter { idOf(it) !in knownIds }

    private fun nextPage(links: List<String>, current: String): String? {
        val cur = pageNumRe.find(current)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val want = (cur + 1).toString()
        return links.firstOrNull { pageNumRe.find(it)?.groupValues?.get(1) == want }
    }

    private fun isContent(u: String, baseTrim: String): Boolean {
        val low = u.lowercase()
        val path = Uri.parse(u).path ?: ""
        if (path.length <= 1) return false
        if (u.trimEnd('/') == baseTrim) return false
        if (pageNumRe.containsMatchIn(u)) return false           // a pagination link, not content
        val bad = listOf(
            "/login", "/signup", "/register", "/search", "/tag/", "/tags/",
            "mailto:", "/about", "/privacy", "/terms", "/feed", "/rss"
        )
        return bad.none { low.contains(it) }
    }
}

/**
 * Habr: a user's posts are paginated at <base>/posts/pageN/. Article links look
 * like /articles/<id>/, /post/<id>/ or /company/<c>/blog/<id>/.
 */
object HabrProfile : SiteProfile {
    override val key = "habr"
    private val idRe = Regex("/(?:articles|post|blog)/(\\d+)/")

    override fun idOf(url: String): String =
        idRe.find(url)?.groupValues?.get(1)?.let { "h$it" } ?: GenericProfile.idOf(url)

    private fun canonical(url: String): String {
        val u = url.substringBefore('#').substringBefore('?')
        val m = idRe.find(u) ?: return u
        return u.substring(0, m.range.last + 1)     // up to and incl. the slash after id
    }

    override suspend fun scanAll(
        r: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        note: (String) -> Unit
    ): List<String> {
        val root = base.trimEnd('/')
        val postsBase = if (root.endsWith("/posts")) root else "$root/posts"
        val out = LinkedHashSet<String>()
        var page = from.coerceAtLeast(1)
        while (!ConvertBus.cancelRequested && page <= 500 && out.size < max) {
            val url = if (page == 1) "$postsBase/" else "$postsBase/page$page/"
            note("Habr: page $page (${out.size} found)")
            val links = (withTimeoutOrNull(P_TIMEOUT_MS) { r.collectAllLinks(url, P_SETTLE_MS) }
                ?: emptyList()).filter { idRe.containsMatchIn(it) }.map { canonical(it) }
            val before = out.size
            links.forEach { out.add(it) }
            if (out.size == before) break        // page added nothing new → end
            ConvertBus.scanProgress(page, out.size)
            page++
        }
        ConvertBus.log("[habr] ${out.size} article(s)")
        return out.toList()
    }

    override suspend fun scanNew(
        r: WebViewPdfRenderer, base: String, step: Int, from: Int, max: Int,
        knownIds: Set<String>, note: (String) -> Unit
    ): List<String> {
        // New articles are on the first pages; stop once everything is known.
        val out = ArrayList<String>()
        val all = scanAll(r, base, step, from, max, note)
        for (u in all) { if (idOf(u) in knownIds) break; out.add(u) }
        return out
    }
}
