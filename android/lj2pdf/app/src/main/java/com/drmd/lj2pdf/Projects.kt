package com.drmd.lj2pdf

import android.content.Context
import android.net.Uri
import java.io.File

/** One archived post: its LJ id, full permalink and (cached) title. */
data class PostEntry(val id: String, val permalink: String, val title: String)

/**
 * A "project" = one archived blog. Stored under
 *   <app files>/projects/<host>/
 *     base.txt      blog base URL
 *     step.txt      entries-per-page used when scanning
 *     index.tsv     ordered (newest-first) list: id<TAB>permalink<TAB>title
 *     posts/<id>.pdf  one rendered PDF per post (reused on update)
 *     book.pdf      the merged book
 *
 * Keeping per-post PDFs is what makes incremental updates cheap: an update
 * only renders the newly-published posts and re-merges.
 */
class Project(val dir: File) {
    val name: String get() = dir.name
    val postsDir: File get() = File(dir, "posts").apply { mkdirs() }
    val bookFile: File get() = File(dir, "book.pdf")
    private val indexFile: File get() = File(dir, "index.tsv")
    private val baseFile: File get() = File(dir, "base.txt")
    private val stepFile: File get() = File(dir, "step.txt")

    var base: String
        get() = if (baseFile.exists()) baseFile.readText().trim() else ""
        set(v) { dir.mkdirs(); baseFile.writeText(v) }

    var step: Int
        get() = stepFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 20
        set(v) { dir.mkdirs(); stepFile.writeText(v.toString()) }

    fun entries(): List<PostEntry> {
        if (!indexFile.exists()) return emptyList()
        return indexFile.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size >= 2) PostEntry(p[0], p[1], if (p.size >= 3) p[2] else "") else null
        }
    }

    fun saveEntries(list: List<PostEntry>) {
        dir.mkdirs()
        indexFile.writeText(list.joinToString("\n") {
            "${it.id}\t${it.permalink}\t${it.title.replace('\t', ' ').replace('\n', ' ')}"
        })
    }

    fun postPdf(id: String): File = File(postsDir, "$id.pdf")
}

object Projects {
    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "projects").apply { mkdirs() }

    fun list(ctx: Context): List<Project> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.map { Project(it) }
            ?.sortedByDescending { it.bookFile.lastModified() } ?: emptyList()

    fun hostOf(base: String): String =
        (Uri.parse(base).host ?: "blog").replace(Regex("[^A-Za-z0-9.-]"), "_")

    fun forBase(ctx: Context, base: String): Project {
        val p = Project(File(root(ctx), hostOf(base)))
        p.dir.mkdirs()
        if (p.base.isBlank()) p.base = base.trimEnd('/')
        return p
    }

    fun delete(p: Project) { p.dir.deleteRecursively() }

    /** Extract the LJ post id from a permalink (…/<digits>.html). */
    fun idOf(permalink: String): String =
        Regex("/(\\d+)\\.html").find(permalink)?.groupValues?.get(1)
            ?: permalink.hashCode().toString()
}
