package com.drmd.lj2pdf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Builds a RAG corpus from a Telegram Desktop export folder.
 *
 * Telegram exports either:
 *   - HTML:  messages.html, messages2.html, … (+ photos/, files/ …), or
 *   - JSON:  result.json
 * We pull the message texts (with sender), chunk them and write JSONL —
 * same format as RagExporter so a local LLM ingests it the same way.
 *
 * The folder is read through SAF (DocumentFile), so it works with a dump the
 * user copied anywhere on the phone.
 */
object TelegramImporter {

    fun export(
        ctx: Context, treeUri: String, outFile: File, chunkSize: Int, overlap: Int,
        progress: (done: Int, total: Int, status: String) -> Unit
    ): Int {
        val root = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) ?: return 0
        val htmls = ArrayList<DocumentFile>()
        var json: DocumentFile? = null
        collect(root, 0) { f ->
            val n = f.name?.lowercase() ?: return@collect
            when {
                n == "result.json" -> json = f
                n.startsWith("messages") && n.endsWith(".html") -> htmls.add(f)
            }
        }
        htmls.sortBy { it.name?.lowercase() ?: "" }   // messages.html, messages2.html, …

        val chatName = root.name?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "telegram"
        var chunkIdx = 0
        var written = 0
        outFile.parentFile?.mkdirs()

        outFile.bufferedWriter().use { w ->
            val sources: List<DocumentFile> =
                if (json != null) listOf(json!!) else htmls
            if (sources.isEmpty()) {
                ConvertBus.log("[tg] no messages*.html or result.json found")
                return 0
            }
            for ((i, f) in sources.withIndex()) {
                if (ConvertBus.cancelRequested) break
                progress(i + 1, sources.size, "Telegram: ${f.name} (${i + 1}/${sources.size})")
                val raw = read(ctx, f) ?: continue
                val text = if (f.name?.endsWith(".json") == true) parseJson(raw) else parseHtml(raw)
                if (text.isBlank()) continue
                for (c in RagExporter.chunk(text, chunkSize, overlap)) {
                    val o = JSONObject()
                    o.put("source", chatName)
                    o.put("file", f.name)
                    o.put("chunk", chunkIdx++)
                    o.put("text", c)
                    w.append(o.toString()).append('\n')
                    written++
                }
            }
        }
        ConvertBus.log("[tg] $written chunk(s) → ${outFile.name}")
        return written
    }

    private fun collect(dir: DocumentFile, depth: Int, onFile: (DocumentFile) -> Unit) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) { if (depth < 2) collect(f, depth + 1, onFile) }
            else onFile(f)
        }
    }

    private fun read(ctx: Context, f: DocumentFile): String? = try {
        ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        ConvertBus.log("[tg] read failed ${f.name}: ${t.message}"); null
    }

    // -- HTML export ------------------------------------------------------

    private fun parseHtml(html: String): String {
        val sb = StringBuilder()
        var lastFrom = ""
        val fromRe = Regex("<div class=\"from_name\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        val textRe = Regex("<div class=\"text\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        for (block in html.split("<div class=\"message").drop(1)) {
            if (block.startsWith(" service")) continue
            fromRe.find(block)?.groupValues?.get(1)?.let {
                val name = stripTags(it); if (name.isNotBlank()) lastFrom = name
            }
            val t = textRe.find(block)?.groupValues?.get(1)?.let { stripTags(it) } ?: ""
            if (t.isNotBlank()) {
                if (lastFrom.isNotBlank()) sb.append(lastFrom).append(": ")
                sb.append(t).append('\n')
            }
        }
        return sb.toString()
    }

    // -- JSON export ------------------------------------------------------

    private fun parseJson(jsonText: String): String {
        return try {
            val root = JSONTokener(jsonText).nextValue() as? JSONObject ?: return ""
            val msgs = root.optJSONArray("messages") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                if (m.optString("type") != "message") continue
                val from = m.optString("from", "")
                val text = jsonText(m.opt("text"))
                if (text.isNotBlank()) {
                    if (from.isNotBlank()) sb.append(from).append(": ")
                    sb.append(text).append('\n')
                }
            }
            sb.toString()
        } catch (t: Throwable) {
            ConvertBus.log("[tg] json parse failed: ${t.message}"); ""
        }
    }

    /** Telegram "text" is a String or an array of strings / {type,text} parts. */
    private fun jsonText(v: Any?): String = when (v) {
        is String -> v
        is JSONArray -> buildString {
            for (i in 0 until v.length()) {
                when (val e = v.get(i)) {
                    is String -> append(e)
                    is JSONObject -> append(e.optString("text"))
                }
            }
        }
        else -> ""
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ").trim()
}
