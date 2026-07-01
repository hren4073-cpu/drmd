package com.drmd.lj2pdf

import android.net.Uri
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enumerates a blog's post permalinks over pooled HTTP + jsoup (no WebView).
 * LiveJournal is scanned in fully-parallel WAVES (years → months → days), each
 * wave fetched with up to [par] concurrent requests, so a whole large blog is
 * mapped in a couple of minutes. Everything else falls back to a generic
 * same-host link crawl that follows pagination.
 */
object SiteScan {

    private fun hostOf(base: String) = (Uri.parse(base).host ?: "").lowercase()
    private fun isLj(base: String) = hostOf(base).contains("livejournal.com")

    private fun links(doc: Document): List<String> =
        doc.select("a[href]").map { it.absUrl("href") }.filter { it.isNotEmpty() }

    /** Canonical post permalinks: …/<digits>.html (drops anchors / query). */
    private fun postPermalinks(all: List<String>, host: String): List<String> {
        val re = Regex("^(https?://([^/]+)/(\\d+))\\.html")
        val out = LinkedHashSet<String>()
        for (u in all) {
            val m = re.find(u) ?: continue
            if (!m.groupValues[2].contains(host)) continue
            out.add(m.groupValues[1] + ".html")
        }
        return out.toList()
    }

    // ---- public entry points -------------------------------------------------

    /** Full archive scan (everything, incl. back-dated posts). */
    suspend fun scanAll(base: String, max: Int, par: Int, note: (String) -> Unit): List<String> =
        if (isLj(base)) scanLj(base, par.coerceIn(1, 32), note).take(max)
        else scanGeneric(base, max, note)

    /** Fast update: only the new top of the feed until a known id appears. */
    suspend fun scanNew(
        base: String, step: Int, knownIds: Set<String>, max: Int, par: Int, note: (String) -> Unit
    ): List<String> {
        if (!isLj(base)) return scanGeneric(base, max, note).filter { Projects.idOf(it) !in knownIds }
        val host = hostOf(base)
        val out = ArrayList<String>(); val seen = HashSet<String>()
        var page = 1
        while (!ConvertBus.cancelRequested && page <= max) {
            val skip = (page - 1) * step
            val url = if (skip == 0) "$base/" else "$base/?skip=$skip"
            note("Checking page $page… (${out.size} new)")
            val doc = Http.doc(url) ?: break
            val posts = postPermalinks(links(doc), host)
            if (posts.isEmpty()) break
            var hitKnown = false
            for (l in posts) {
                if (Projects.idOf(l) in knownIds) { hitKnown = true; break }
                if (seen.add(l)) out.add(l)
            }
            if (hitKnown) break
            ConvertBus.scanProgress(page, out.size)
            page++
        }
        return out
    }

    // ---- LiveJournal archive (parallel waves) --------------------------------

    private suspend fun scanLj(base: String, par: Int, note: (String) -> Unit): List<String> {
        val host = hostOf(base)
        val monthRe = Regex("^https?://[^/]+/(\\d{4})/(\\d{2})/?$")
        val dayRe = Regex("^https?://[^/]+/(\\d{4})/(\\d{2})/(\\d{2})/?$")
        val yearRe = Regex("^https?://[^/]+/(\\d{4})/?$")

        ConvertBus.log("[scan] reading archive… ($par threads)")
        val cal = Http.doc("$base/calendar")?.let { links(it) } ?: emptyList()
        val calMonths = cal.filter { monthRe.matches(it) }.toMutableSet()
        val curYear = Calendar.getInstance().get(Calendar.YEAR)
        // Candidate years = every year the calendar links to (incl. back-dated) +
        // a recent 30-year window as a safety net. Probed all at once.
        val calYears = cal.filter { yearRe.matches(it) }.map { yr(it) }.filter { it in 1900..curYear }
        val years = (calYears + (curYear - 30..curYear)).filter { it in 1900..curYear }
            .toSortedSet(Comparator.reverseOrder())

        // Wave 1: all /YYYY/ pages in parallel → month links.
        note("Scanning ${years.size} year(s)…")
        val yearMonths = years.toList().mapPar(par) { y ->
            if (ConvertBus.cancelRequested) emptyList()
            else (Http.doc("$base/$y/")?.let { links(it) } ?: emptyList()).filter { monthRe.matches(it) }
        }.flatten()

        val months = (calMonths + yearMonths).distinct().sortedByDescending { ym(it) }
        if (months.isEmpty()) {
            ConvertBus.log("[scan] no archive months — using ?skip= (may be limited)")
            return scanNew(base, 20, emptySet(), 2000, par, note)
        }
        ConvertBus.log("[scan] archive has ${months.size} month(s)")

        // Wave 2: all /YYYY/MM/ pages in parallel → posts, or day links.
        val monthDone = AtomicInteger(0)
        val monthResults = months.mapPar(par) { m ->
            if (ConvertBus.cancelRequested) return@mapPar Pair(emptyList(), emptyList<String>())
            val doc = Http.doc(m)
            val posts = doc?.let { postPermalinks(links(it), host) } ?: emptyList()
            val days = if (posts.isEmpty())
                (doc?.let { links(it) } ?: emptyList()).filter { dayRe.matches(it) } else emptyList()
            val n = monthDone.incrementAndGet()
            ConvertBus.scanProgress(n, posts.size)
            note("Scanning months $n/${months.size}…")
            Pair(posts, days)
        }
        val directPosts = monthResults.flatMap { it.first }
        val dayUrls = monthResults.flatMap { it.second }.distinct()

        // Wave 3: day-grouped months → all /YYYY/MM/DD/ pages in parallel.
        val dayPosts = if (dayUrls.isEmpty()) emptyList() else {
            ConvertBus.log("[scan] ${dayUrls.size} day page(s) to open")
            dayUrls.mapPar(par) { d ->
                if (ConvertBus.cancelRequested) emptyList()
                else Http.doc(d)?.let { postPermalinks(links(it), host) } ?: emptyList()
            }.flatten()
        }

        val all = (directPosts + dayPosts).distinct()
            .sortedByDescending { Projects.idOf(it).toLongOrNull() ?: 0L }
        ConvertBus.log("[scan] archive total: ${all.size} post(s)")
        return all
    }

    // ---- generic crawl (Habr / other index pages) ----------------------------

    private val pageNumRe = Regex("(?:[?&]page=|[?&]p=|/page/)(\\d+)")

    private suspend fun scanGeneric(base: String, max: Int, note: (String) -> Unit): List<String> {
        val host = hostOf(base)
        val baseTrim = base.trimEnd('/')
        val items = LinkedHashSet<String>()
        val seenPages = HashSet<String>()
        var pageUrl: String? = base
        var visited = 0
        while (pageUrl != null && visited < 300 && items.size < max && !ConvertBus.cancelRequested) {
            if (!seenPages.add(pageUrl)) break
            visited++
            note("Scanning page $visited (${items.size} items)…")
            val all = Http.doc(pageUrl)?.let { links(it) } ?: emptyList()
            all.filter { isContent(it, host, baseTrim) }.forEach { items.add(it) }
            ConvertBus.scanProgress(visited, items.size)
            val cur = pageNumRe.find(pageUrl!!)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val want = (cur + 1).toString()
            pageUrl = all.firstOrNull { pageNumRe.find(it)?.groupValues?.get(1) == want }
        }
        val list = if (items.isEmpty()) listOf(base) else items.toList().take(max)
        ConvertBus.log("[scan] ${list.size} item(s) over $visited page(s)")
        return list
    }

    private fun isContent(u: String, host: String, baseTrim: String): Boolean {
        val low = u.lowercase()
        if (!low.contains(host)) return false
        val path = Uri.parse(u).path ?: ""
        if (path.length <= 1) return false
        if (u.trimEnd('/') == baseTrim) return false
        if (pageNumRe.containsMatchIn(u)) return false
        val bad = listOf(
            "/login", "/signup", "/register", "/search", "/tag/", "/tags/",
            "mailto:", "/about", "/privacy", "/terms", "/feed", "/rss", "/profile"
        )
        return bad.none { low.contains(it) }
    }

    // ---- helpers --------------------------------------------------------------

    private fun yr(url: String): Int =
        Regex("/(\\d{4})").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun ym(url: String): Int {
        val m = Regex("/(\\d{4})/(\\d{2})").find(url) ?: return 0
        return m.groupValues[1].toInt() * 100 + m.groupValues[2].toInt()
    }

    private fun ymd(url: String): Int {
        val m = Regex("/(\\d{4})/(\\d{2})/(\\d{2})").find(url) ?: return 0
        return (m.groupValues[1].toInt() * 100 + m.groupValues[2].toInt()) * 100 +
            m.groupValues[3].toInt()
    }
}

/** Map [this] with at most [n] concurrent suspend calls, preserving order. */
suspend fun <T, R> Iterable<T>.mapPar(n: Int, f: suspend (T) -> R): List<R> = coroutineScope {
    val sem = Semaphore(n.coerceAtLeast(1))
    map { item -> async { sem.withPermit { f(item) } } }.awaitAll()
}
