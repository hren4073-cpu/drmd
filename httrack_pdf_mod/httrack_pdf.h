/* ------------------------------------------------------------ */
/*
   httrack_pdf.h - PDF export / article-cleaning module for HTTrack
   Part of the HTTrack Website Copier modification.

   This module adds:
     - HTML cleaning (LiveJournal-specific + generic ad/clutter heuristics)
       applied on the fly via the "postprocess-html" callback,
     - post-mirror conversion of every saved .html/.htm into PDF using a
       headless Chromium/Edge (--headless=new --print-to-pdf),
     - optional merge of all PDFs into a single book with a bookmark TOC
       (via Ghostscript).

   It can be compiled in three ways:
     1) As an HTTrack external wrapper plugin (.so/.dll) exporting hts_plug().
     2) Linked into libhttrack and driven by native --pdf-* options
        (call httrack_pdf_init() from htscoremain.c).
     3) As a self-contained standalone CLI (-DHTSPDF_STANDALONE) that needs
        no HTTrack headers - handy for testing on an already-downloaded mirror.

   License: GNU GPL v2 or later (same as HTTrack).
*/
/* ------------------------------------------------------------ */

#ifndef HTTRACK_PDF_H
#define HTTRACK_PDF_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* HTML cleaning strategy. */
typedef enum {
  HTSPDF_CLEAN_OFF = 0,   /* keep page as downloaded                       */
  HTSPDF_CLEAN_GENERIC,   /* heuristic ad/clutter removal (Readability-ish) */
  HTSPDF_CLEAN_LJ         /* LiveJournal: extract the article body only     */
} htspdf_clean_mode;

/* Module configuration (filled from the wrapper arg string or --pdf-* opts). */
typedef struct htspdf_config {
  int  enabled;            /* master switch: do the PDF export at end       */
  int  do_merge;           /* merge all PDFs into one book                  */
  int  no_images;          /* strip <img>/<picture> during cleaning        */
  int  concurrency;        /* parallel headless processes (1..8)            */
  int  timeout_sec;        /* per-file conversion timeout (seconds)         */
  htspdf_clean_mode clean; /* cleaning strategy                            */
  char page_size[16];      /* "A4", "Letter", "Legal", "A3", ... ("" = def) */
  char chrome_path[1024];  /* explicit browser path ("" = autodetect)      */
  char gs_path[1024];      /* explicit ghostscript path ("" = autodetect)  */
  char out_dir[1024];      /* mirror root to process ("" = derive from opt) */
  char merge_file[1024];   /* merged output path ("" = <out_dir>/book.pdf)  */
  /* Extra CSS injected into every page before printing ("" = none). */
  char extra_css_class[2048]; /* additional kill class/id substrings, csv  */
} htspdf_config;

/* Parse a wrapper/option string such as:
   "export,merge,clean=lj,pagesize=A4,concurrency=4,noimg,timeout=30"
   into cfg (cfg is reset to defaults first). Returns 1 on success. */
int htspdf_config_parse(htspdf_config *cfg, const char *args);

/* Reset cfg to sane defaults. */
void htspdf_config_defaults(htspdf_config *cfg);

/* -------- HTML cleaning (pure, no HTTrack dependency) -------- */
/* Returns a freshly malloc()'d cleaned copy of `html` (length `len`).
   *out_len receives the new length. Caller frees with free().
   Never returns NULL on success; on allocation failure returns NULL. */
char *htspdf_clean_html(const char *html, size_t len,
                        const htspdf_config *cfg, size_t *out_len);

/* Extract a human title (from <title> or first <h1>) into `title`
   (size `title_size`). Returns 1 if a title was found. */
int htspdf_extract_title(const char *html, size_t len,
                         char *title, size_t title_size);

/* -------- Post-processing pipeline (Chrome + merge) -------- */
/* Walk `root`, convert every .html/.htm file to a .pdf next to the source,
   honoring cfg (concurrency, timeout, page size). Writes errors to
   <root>/pdf_errors.log. Returns number of files successfully converted. */
int htspdf_export_dir(const char *root, const htspdf_config *cfg);

/* -------- HTTrack glue (omitted in standalone builds) -------- */
#ifndef HTSPDF_STANDALONE
/* Chain the cleaning + end-of-mirror callbacks onto `opt`.
   `args` is the option/wrapper string (may be NULL). Returns 1 on success.
   Call this from htscoremain.c (native build) or from hts_plug (plugin). */
struct httrackp;
int httrack_pdf_init(struct httrackp *opt, const char *args);
#endif

#ifdef __cplusplus
}
#endif

#endif /* HTTRACK_PDF_H */
