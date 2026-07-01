package com.drmd.lj2pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File

/**
 * Turns archived project(s) into a RAG corpus for local LLMs.
 *
 * Text is extracted straight from the per-post PDFs (PDFBox PDFTextStripper —
 * the WebView-rendered PDFs contain real text), split into overlapping chunks,
 * and written as JSONL — one record per chunk:
 *   {"source","id","title","url","chunk","text"}
 *
 * The phone produces the dataset; the local side computes the embeddings /
 * vector DB (LangChain, llama-index, Ollama, etc. ingest JSONL directly).
 * Several projects can be merged into one corpus.
 */
object RagExporter {

    /**
     * @return number of documents written. Output is JSONL at [outFile].
     * [engine] = "jsonl" (flat) or "mempalace" (hierarchical Wings/Halls/Rooms
     * bundle a PC MemPalace install can ingest — one NDJSON record per chunk).
     */
    fun export(
        projects: List<Project>, outFile: File, chunkSize: Int, overlap: Int,
        engine: String = "jsonl",
        progress: (done: Int, total: Int, status: String) -> Unit
    ): Int {
        val mempalace = engine == "mempalace"
        val total = projects.sumOf { it.entries().size }
        var done = 0
        var docs = 0
        var chunksOut = 0
        outFile.parentFile?.mkdirs()
        outFile.bufferedWriter().use { w ->
            for (p in projects) {
                for (e in p.entries()) {
                    if (ConvertBus.cancelRequested) return docs
                    done++
                    progress(done, total, "RAG: ${p.name} — $done/$total")
                    val text = extractText(p, e.id)
                    if (text.isBlank()) continue
                    val chunks = chunk(text, chunkSize, overlap)
                    for ((k, c) in chunks.withIndex()) {
                        val o = JSONObject()
                        if (mempalace) {
                            // Spatial hierarchy: Wing=blog, Hall=posts, Room=post.
                            o.put("wing", p.name)
                            o.put("hall", "posts")
                            o.put("room", e.title.ifBlank { "post_${e.id}" })
                            o.put("drawer", "${e.id}#$k")
                            o.put("url", e.permalink)
                            o.put("text", c)
                        } else {
                            o.put("source", p.name)
                            o.put("id", e.id)
                            o.put("title", e.title)
                            o.put("url", e.permalink)
                            o.put("chunk", k)
                            o.put("text", c)
                        }
                        w.append(o.toString()).append('\n')
                        chunksOut++
                    }
                    docs++
                }
            }
        }
        ConvertBus.log("[rag] $docs doc(s), $chunksOut chunk(s) → ${outFile.name}")
        return docs
    }

    /** Prefer the downloaded HTML base (clean text); fall back to the post PDF. */
    private fun extractText(p: Project, id: String): String {
        val html = p.postHtml(id)
        if (html.exists() && html.length() > 0) {
            try {
                return Jsoup.parse(html, "UTF-8").body()?.text().orEmpty()
            } catch (t: Throwable) {
                ConvertBus.log("[rag] html parse failed for ${html.name}: ${t.message}")
            }
        }
        val pdf = p.postPdf(id)
        if (!pdf.exists() || pdf.length() == 0L) return ""
        return try {
            PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        } catch (t: Throwable) {
            ConvertBus.log("[rag] text extract failed for ${pdf.name}: ${t.message}")
            ""
        }
    }

    /** Split into ~[size]-char chunks on whitespace, overlapping by [overlap]. */
    fun chunk(raw: String, size: Int, overlap: Int): List<String> {
        val text = raw.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n").trim()
        if (text.isEmpty()) return emptyList()
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        var i = 0
        val step = (size - overlap).coerceAtLeast(1)
        while (i < text.length) {
            var end = (i + size).coerceAtMost(text.length)
            if (end < text.length) {
                // prefer to break on a space near the end
                val sp = text.lastIndexOf(' ', end)
                if (sp > i + step / 2) end = sp
            }
            out.add(text.substring(i, end).trim())
            if (end >= text.length) break
            i = end - overlap
            if (i < 0) i = 0
        }
        return out.filter { it.isNotEmpty() }
    }
}
