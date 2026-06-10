/* ------------------------------------------------------------ */
/*
   httrack_pdf.c - PDF export / article-cleaning module for HTTrack.
   See httrack_pdf.h for an overview and build modes.

   Style follows the HTTrack code base (C99, K&R-ish braces, English
   comments). Platform code is guarded by _WIN32 (WinAPI) vs POSIX.

   License: GNU GPL v2 or later (same as HTTrack).
*/
/* ------------------------------------------------------------ */

/* Expose POSIX strdup()/strtok_r()/realpath() under -std=c99. */
#ifndef _WIN32
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif
#ifndef _DEFAULT_SOURCE
#define _DEFAULT_SOURCE 1
#endif
#endif

#include "httrack_pdf.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <time.h>
#include <errno.h>
#include <stdarg.h>

#ifdef _WIN32
#include <windows.h>
#include <direct.h>
#define HTSPDF_PATHSEP '\\'
#define popen  _popen
#define pclose _pclose
#define strcasecmp _stricmp
#else
#include <strings.h>            /* strcasecmp */
#include <unistd.h>
#include <dirent.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <signal.h>
#include <fcntl.h>
#include <limits.h>
#define HTSPDF_PATHSEP '/'
#endif

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/* ============================================================ */
/*  Small dynamic string buffer                                  */
/* ============================================================ */

typedef struct {
  char  *p;
  size_t len;
  size_t cap;
} sbuf;

static int sb_reserve(sbuf *b, size_t extra) {
  if (b->len + extra + 1 > b->cap) {
    size_t ncap = b->cap ? b->cap * 2 : 4096;
    while (ncap < b->len + extra + 1)
      ncap *= 2;
    char *np = (char *) realloc(b->p, ncap);
    if (np == NULL)
      return 0;
    b->p = np;
    b->cap = ncap;
  }
  return 1;
}

static int sb_putn(sbuf *b, const char *s, size_t n) {
  if (!sb_reserve(b, n))
    return 0;
  memcpy(b->p + b->len, s, n);
  b->len += n;
  b->p[b->len] = '\0';
  return 1;
}

static int sb_puts(sbuf *b, const char *s) {
  return sb_putn(b, s, strlen(s));
}

static int sb_putc(sbuf *b, char c) {
  if (!sb_reserve(b, 1))
    return 0;
  b->p[b->len++] = c;
  b->p[b->len] = '\0';
  return 1;
}

/* ============================================================ */
/*  Configuration parsing                                        */
/* ============================================================ */

void htspdf_config_defaults(htspdf_config *cfg) {
  memset(cfg, 0, sizeof(*cfg));
  cfg->enabled = 1;
  cfg->do_merge = 0;
  cfg->no_images = 0;
  cfg->concurrency = 4;
  cfg->timeout_sec = 30;
  cfg->clean = HTSPDF_CLEAN_GENERIC;
  strcpy(cfg->page_size, "A4");
}

static void str_tolower(char *s) {
  for (; *s; s++)
    *s = (char) tolower((unsigned char) *s);
}

/* Parse "key=value" or bare "flag" tokens separated by ',' or ';'. */
int htspdf_config_parse(htspdf_config *cfg, const char *args) {
  htspdf_config_defaults(cfg);
  if (args == NULL || *args == '\0')
    return 1;

  char *copy = strdup(args);
  if (copy == NULL)
    return 0;

  char *save = NULL;
  char *tok;
#ifdef _WIN32
  for (tok = strtok(copy, ",;"); tok != NULL; tok = strtok(NULL, ",;")) {
#else
  for (tok = strtok_r(copy, ",;", &save); tok != NULL;
       tok = strtok_r(NULL, ",;", &save)) {
#endif
    while (*tok == ' ')
      tok++;
    char key[64], *val = strchr(tok, '=');
    if (val != NULL) {
      size_t kl = (size_t) (val - tok);
      if (kl >= sizeof(key))
        kl = sizeof(key) - 1;
      memcpy(key, tok, kl);
      key[kl] = '\0';
      val++;
    } else {
      strncpy(key, tok, sizeof(key) - 1);
      key[sizeof(key) - 1] = '\0';
    }
    str_tolower(key);

    if (strcmp(key, "export") == 0 || strcmp(key, "pdf") == 0) {
      cfg->enabled = 1;
    } else if (strcmp(key, "merge") == 0) {
      cfg->do_merge = 1;
    } else if (strcmp(key, "noimg") == 0 || strcmp(key, "no-images") == 0) {
      cfg->no_images = 1;
    } else if (strcmp(key, "concurrency") == 0 && val) {
      cfg->concurrency = atoi(val);
      if (cfg->concurrency < 1) cfg->concurrency = 1;
      if (cfg->concurrency > 8) cfg->concurrency = 8;
    } else if (strcmp(key, "timeout") == 0 && val) {
      cfg->timeout_sec = atoi(val);
      if (cfg->timeout_sec < 5) cfg->timeout_sec = 5;
    } else if ((strcmp(key, "pagesize") == 0 || strcmp(key, "page-size") == 0)
               && val) {
      strncpy(cfg->page_size, val, sizeof(cfg->page_size) - 1);
    } else if (strcmp(key, "clean") == 0 && val) {
      if (strcmp(val, "lj") == 0 || strcmp(val, "livejournal") == 0)
        cfg->clean = HTSPDF_CLEAN_LJ;
      else if (strcmp(val, "off") == 0 || strcmp(val, "none") == 0)
        cfg->clean = HTSPDF_CLEAN_OFF;
      else
        cfg->clean = HTSPDF_CLEAN_GENERIC;
    } else if (strcmp(key, "chrome") == 0 && val) {
      strncpy(cfg->chrome_path, val, sizeof(cfg->chrome_path) - 1);
    } else if (strcmp(key, "gs") == 0 && val) {
      strncpy(cfg->gs_path, val, sizeof(cfg->gs_path) - 1);
    } else if ((strcmp(key, "outdir") == 0 || strcmp(key, "out") == 0) && val) {
      strncpy(cfg->out_dir, val, sizeof(cfg->out_dir) - 1);
    } else if (strcmp(key, "mergefile") == 0 && val) {
      strncpy(cfg->merge_file, val, sizeof(cfg->merge_file) - 1);
    } else if (strcmp(key, "kill") == 0 && val) {
      strncpy(cfg->extra_css_class, val, sizeof(cfg->extra_css_class) - 1);
    }
  }
  free(copy);
  return 1;
}

/* ============================================================ */
/*  HTML tag scanner + cleaner                                   */
/* ============================================================ */

/* Case-insensitive memmem-ish search. */
static const char *ci_find(const char *hay, size_t hlen, const char *needle) {
  size_t nlen = strlen(needle);
  if (nlen == 0 || nlen > hlen)
    return NULL;
  for (size_t i = 0; i + nlen <= hlen; i++) {
    size_t k = 0;
    for (; k < nlen; k++) {
      char a = (char) tolower((unsigned char) hay[i + k]);
      char b = (char) tolower((unsigned char) needle[k]);
      if (a != b)
        break;
    }
    if (k == nlen)
      return hay + i;
  }
  return NULL;
}

static int is_void_element(const char *name) {
  static const char *voids[] = {
    "img", "br", "hr", "input", "meta", "link", "source", "col",
    "area", "base", "embed", "param", "track", "wbr", NULL
  };
  for (int i = 0; voids[i]; i++)
    if (strcmp(name, voids[i]) == 0)
      return 1;
  return 0;
}

/* Raw-text elements whose content must be skipped to the literal close. */
static int is_rawtext_element(const char *name) {
  return strcmp(name, "script") == 0 || strcmp(name, "noscript") == 0 ||
         strcmp(name, "textarea") == 0;
}

/* Parse a tag starting at s[i]=='<'. Fills:
   *end  = index just past '>',
   name  = lowercased tag name (without '<' '/'),
   *is_close, *self_close.
   Returns 1 on success (a real tag), 0 if it's not a tag (e.g. "< ").
   Comments/CDATA/doctype are reported with name="!". */
static int parse_tag(const char *s, size_t len, size_t i, size_t *end,
                     char *name, size_t name_size, int *is_close,
                     int *self_close) {
  *is_close = 0;
  *self_close = 0;
  name[0] = '\0';
  if (i + 1 >= len || s[i] != '<')
    return 0;

  /* Comment / CDATA / doctype / processing instr. */
  if (s[i + 1] == '!' || s[i + 1] == '?') {
    if (i + 3 < len && s[i + 1] == '!' && s[i + 2] == '-' && s[i + 3] == '-') {
      const char *c = ci_find(s + i + 4, len - (i + 4), "-->");
      *end = c ? (size_t) (c - s) + 3 : len;
    } else {
      size_t j = i + 1;
      while (j < len && s[j] != '>')
        j++;
      *end = (j < len) ? j + 1 : len;
    }
    strncpy(name, "!", name_size);
    return 1;
  }

  size_t j = i + 1;
  if (j < len && s[j] == '/') {
    *is_close = 1;
    j++;
  }
  if (j >= len || !(isalpha((unsigned char) s[j]))) {
    return 0;                   /* not a tag, e.g. "a < b" */
  }
  /* read name */
  size_t k = 0;
  while (j < len && (isalnum((unsigned char) s[j]) || s[j] == '-' ||
                     s[j] == ':' || s[j] == '_')) {
    if (k + 1 < name_size)
      name[k++] = (char) tolower((unsigned char) s[j]);
    j++;
  }
  name[k] = '\0';

  /* skip to '>' honoring quoted attribute values */
  char quote = 0;
  while (j < len) {
    char c = s[j];
    if (quote) {
      if (c == quote)
        quote = 0;
    } else if (c == '"' || c == '\'') {
      quote = c;
    } else if (c == '>') {
      if (j > i && s[j - 1] == '/')
        *self_close = 1;
      j++;
      break;
    }
    j++;
  }
  *end = j;
  return 1;
}

/* Collect class="" and id="" attribute values from a tag's text [s,e). */
static void collect_attr_values(const char *tag, size_t tlen, sbuf *out) {
  static const char *attrs[] = { "class", "id", NULL };
  for (int a = 0; attrs[a]; a++) {
    const char *p = tag;
    size_t remain = tlen;
    const char *hit;
    while ((hit = ci_find(p, remain, attrs[a])) != NULL) {
      const char *q = hit + strlen(attrs[a]);
      size_t qrem = tlen - (size_t) (q - tag);
      /* must be "attr=" possibly with spaces */
      while (qrem && (*q == ' ' || *q == '\t')) { q++; qrem--; }
      if (qrem && *q == '=') {
        q++; qrem--;
        while (qrem && (*q == ' ' || *q == '\t')) { q++; qrem--; }
        char quote = 0;
        if (qrem && (*q == '"' || *q == '\'')) { quote = *q; q++; qrem--; }
        const char *vstart = q;
        while (qrem) {
          if (quote) {
            if (*q == quote) break;
          } else if (*q == ' ' || *q == '>' || *q == '\t' || *q == '/') {
            break;
          }
          q++; qrem--;
        }
        sb_putn(out, vstart, (size_t) (q - vstart));
        sb_putc(out, ' ');
      }
      p = hit + strlen(attrs[a]);
      remain = tlen - (size_t) (p - tag);
      if (remain == 0)
        break;
    }
  }
}

/* Decide whether an element with these class/id values is clutter. */
static int attrs_are_clutter(const char *vals, const htspdf_config *cfg) {
  /* exact-token kill list */
  static const char *exact[] = {
    "ad", "ads", "sidebar", "footer", "nav", "menu", "aside", "banner",
    "promo", "popup", "overlay", "widget", "share", "social", "comment",
    "comments", "related", "newsletter", "cookie", "breadcrumb", NULL
  };
  /* substring kill list (more specific to avoid false positives) */
  static const char *substr[] = {
    "advert", "reklama", "banner", "sidebar", "comment", "popup",
    "overlay", "cookie-", "widget", "newsletter", "sponsor", "b-ads",
    "b-reklama", "social", "sharing", "share-", "-ad-", "adsbygoogle",
    "promo", "subscribe", NULL
  };

  /* tokenize vals by whitespace */
  char buf[1024];
  strncpy(buf, vals, sizeof(buf) - 1);
  buf[sizeof(buf) - 1] = '\0';

  char *save = NULL, *t;
#ifdef _WIN32
  for (t = strtok(buf, " \t\r\n"); t; t = strtok(NULL, " \t\r\n")) {
#else
  for (t = strtok_r(buf, " \t\r\n", &save); t;
       t = strtok_r(NULL, " \t\r\n", &save)) {
#endif
    char low[256];
    strncpy(low, t, sizeof(low) - 1);
    low[sizeof(low) - 1] = '\0';
    str_tolower(low);
    for (int i = 0; exact[i]; i++)
      if (strcmp(low, exact[i]) == 0)
        return 1;
    for (int i = 0; substr[i]; i++)
      if (strstr(low, substr[i]) != NULL)
        return 1;
    if (cfg->extra_css_class[0]) {
      /* user supplied comma-separated extra substrings */
      char extra[2048];
      strncpy(extra, cfg->extra_css_class, sizeof(extra) - 1);
      extra[sizeof(extra) - 1] = '\0';
      str_tolower(extra);
      char *es = NULL, *ek;
#ifdef _WIN32
      for (ek = strtok(extra, ","); ek; ek = strtok(NULL, ",")) {
#else
      for (ek = strtok_r(extra, ",", &es); ek; ek = strtok_r(NULL, ",", &es)) {
#endif
        if (*ek && strstr(low, ek) != NULL)
          return 1;
      }
    }
  }
  return 0;
}

/* Build the injected print stylesheet (page size + print colors). */
static void build_inject_css(const htspdf_config *cfg, sbuf *css) {
  sb_puts(css, "<style id=\"htspdf-print\">");
  if (cfg->page_size[0]) {
    sb_puts(css, "@page{size:");
    sb_puts(css, cfg->page_size);
    sb_puts(css, ";margin:12mm;}");
  }
  /* force backgrounds/images to print, keep article readable */
  sb_puts(css,
          "html,body{background:#fff!important;}"
          "*{-webkit-print-color-adjust:exact!important;"
          "print-color-adjust:exact!important;}"
          "img{max-width:100%!important;height:auto!important;}");
  sb_puts(css, "</style>");
}

/* Generic cleaner: removes script/noscript/iframe/svg/object, clutter
   elements (by class/id), optionally images; injects print CSS in <head>. */
static char *clean_generic(const char *s, size_t len,
                           const htspdf_config *cfg, size_t *out_len) {
  sbuf out = { 0 };
  sbuf css = { 0 };
  build_inject_css(cfg, &css);

  int injected = 0;
  size_t i = 0;
  char name[64];

  while (i < len) {
    if (s[i] != '<') {
      sb_putc(&out, s[i]);
      i++;
      continue;
    }
    size_t end;
    int is_close, self_close;
    if (!parse_tag(s, len, i, &end, name, sizeof(name), &is_close,
                   &self_close)) {
      sb_putc(&out, s[i]);
      i++;
      continue;
    }

    /* comments / doctype: keep doctype, drop comments */
    if (name[0] == '!') {
      if (i + 3 < len && s[i + 1] == '!' && (s[i + 2] != '-')) {
        sb_putn(&out, s + i, end - i);     /* <!DOCTYPE ...> */
      }
      i = end;
      continue;
    }

    int kill_block = 0;
    if (!is_close) {
      if (strcmp(name, "script") == 0 || strcmp(name, "noscript") == 0 ||
          strcmp(name, "iframe") == 0 || strcmp(name, "svg") == 0 ||
          strcmp(name, "object") == 0 || strcmp(name, "embed") == 0 ||
          strcmp(name, "canvas") == 0 || strcmp(name, "form") == 0) {
        kill_block = 1;
      } else if (cfg->no_images &&
                 (strcmp(name, "img") == 0 || strcmp(name, "picture") == 0 ||
                  strcmp(name, "figure") == 0)) {
        if (is_void_element(name)) {        /* <img> */
          i = end;
          continue;
        }
        kill_block = 1;
      } else if (cfg->clean != HTSPDF_CLEAN_OFF &&
                 (strcmp(name, "nav") == 0 || strcmp(name, "aside") == 0)) {
        /* semantic navigation / sidebar containers */
        kill_block = 1;
      } else if (cfg->clean != HTSPDF_CLEAN_OFF) {
        sbuf vals = { 0 };
        collect_attr_values(s + i, end - i, &vals);
        if (vals.p && attrs_are_clutter(vals.p, cfg)) {
          if (is_void_element(name)) {
            free(vals.p);
            i = end;
            continue;
          }
          kill_block = 1;
        }
        free(vals.p);
      }
    }

    if (kill_block) {
      /* skip up to the matching close tag */
      if (is_rawtext_element(name) || is_void_element(name)) {
        char close[80];
        snprintf(close, sizeof(close), "</%s", name);
        const char *c = ci_find(s + end, len - end, close);
        if (c == NULL) {
          i = len;
        } else {
          size_t cj = (size_t) (c - s);
          while (cj < len && s[cj] != '>')
            cj++;
          i = (cj < len) ? cj + 1 : len;
        }
      } else {
        int depth = 1;
        size_t j = end;
        char nm[64];
        while (j < len && depth > 0) {
          if (s[j] != '<') {
            j++;
            continue;
          }
          size_t e2;
          int ic, sc;
          if (!parse_tag(s, len, j, &e2, nm, sizeof(nm), &ic, &sc)) {
            j++;
            continue;
          }
          if (nm[0] != '!' && strcmp(nm, name) == 0) {
            if (ic)
              depth--;
            else if (!sc && !is_void_element(nm))
              depth++;
          }
          j = e2;
        }
        i = j;
      }
      continue;
    }

    /* keep this tag */
    sb_putn(&out, s + i, end - i);
    /* inject print CSS right after <head> (or before </head> fallback) */
    if (!injected && strcmp(name, "head") == 0 && !is_close) {
      sb_putn(&out, css.p, css.len);
      injected = 1;
    }
    i = end;
  }

  if (!injected && css.len) {
    /* no <head> found: prepend stylesheet */
    sbuf wrap = { 0 };
    sb_putn(&wrap, css.p, css.len);
    sb_putn(&wrap, out.p ? out.p : "", out.len);
    free(out.p);
    out = wrap;
  }

  free(css.p);
  if (out.p == NULL) {
    out.p = strdup("");
    out.len = 0;
  }
  *out_len = out.len;
  return out.p;
}

/* Locate the article body for LiveJournal-like blogs and rebuild a minimal
   document. Falls back to generic cleaning if the marker is not found. */
static char *clean_livejournal(const char *s, size_t len,
                               const htspdf_config *cfg, size_t *out_len) {
  static const char *markers[] = {
    "entry-content", "b-singlepost-body", "aentry-post__text",
    "j-e-text", "articleBody", "post__text", "entryContent", NULL
  };

  /* find <head>..</head> (kept verbatim, CSS preserved) */
  const char *hs = ci_find(s, len, "<head");
  const char *he = ci_find(s, len, "</head>");
  size_t head_off = 0, head_end = 0;
  if (hs && he && he > hs) {
    head_off = (size_t) (hs - s);
    head_end = (size_t) (he - s) + 7;
  }

  /* scan for the content element */
  size_t i = 0, cstart = 0, cend = 0;
  char name[64], cname[64] = "";
  while (i < len) {
    if (s[i] != '<') { i++; continue; }
    size_t end;
    int ic, sc;
    if (!parse_tag(s, len, i, &end, name, sizeof(name), &ic, &sc)) { i++; continue; }
    if (!ic && name[0] != '!') {
      sbuf vals = { 0 };
      collect_attr_values(s + i, end - i, &vals);
      if (vals.p) {
        char low[1024];
        strncpy(low, vals.p, sizeof(low) - 1);
        low[sizeof(low) - 1] = '\0';
        str_tolower(low);
        for (int m = 0; markers[m]; m++) {
          char lm[64];
          strncpy(lm, markers[m], sizeof(lm) - 1);
          lm[sizeof(lm) - 1] = '\0';
          str_tolower(lm);
          if (strstr(low, lm)) {
            cstart = i;
            strcpy(cname, name);
            break;
          }
        }
      }
      free(vals.p);
      if (cstart)
        break;
    }
    i = end;
  }

  if (!cstart) {
    /* not a recognizable article -> generic cleaning */
    return clean_generic(s, len, cfg, out_len);
  }

  /* find matching close of cname */
  {
    int depth = 1;
    size_t j;
    /* move j to just after the content start tag */
    size_t e0; int ic0, sc0; char nm0[64];
    parse_tag(s, len, cstart, &e0, nm0, sizeof(nm0), &ic0, &sc0);
    j = e0;
    char nm[64];
    while (j < len && depth > 0) {
      if (s[j] != '<') { j++; continue; }
      size_t e2; int ic, sc;
      if (!parse_tag(s, len, j, &e2, nm, sizeof(nm), &ic, &sc)) { j++; continue; }
      if (nm[0] != '!' && strcmp(nm, cname) == 0) {
        if (ic) depth--;
        else if (!sc && !is_void_element(nm)) depth++;
      }
      j = e2;
    }
    cend = j;
  }

  /* compose minimal document: doctype + head + body(article) */
  sbuf doc = { 0 };
  sb_puts(&doc, "<!DOCTYPE html>\n<html>\n");
  if (head_end > head_off) {
    sb_putn(&doc, s + head_off, head_end - head_off);
  } else {
    sb_puts(&doc, "<head><meta charset=\"utf-8\"></head>");
  }
  sb_puts(&doc, "\n<body>\n");
  sb_putn(&doc, s + cstart, cend - cstart);
  sb_puts(&doc, "\n</body>\n</html>\n");

  /* run generic cleaning over the extracted article (drops nested ads, JS,
     and injects the print CSS) */
  char *res = clean_generic(doc.p, doc.len, cfg, out_len);
  free(doc.p);
  return res;
}

char *htspdf_clean_html(const char *html, size_t len,
                        const htspdf_config *cfg, size_t *out_len) {
  if (cfg->clean == HTSPDF_CLEAN_LJ)
    return clean_livejournal(html, len, cfg, out_len);
  return clean_generic(html, len, cfg, out_len);
}

/* ============================================================ */
/*  Title extraction                                             */
/* ============================================================ */

static void strip_collapse(const char *src, size_t n, char *dst, size_t cap) {
  size_t o = 0;
  int prev_space = 1;           /* trim leading */
  for (size_t i = 0; i < n && o + 1 < cap; i++) {
    unsigned char c = (unsigned char) src[i];
    if (c == '\r' || c == '\n' || c == '\t' || c == ' ') {
      if (!prev_space) { dst[o++] = ' '; prev_space = 1; }
    } else {
      dst[o++] = (char) c;
      prev_space = 0;
    }
  }
  while (o > 0 && dst[o - 1] == ' ')
    o--;
  dst[o] = '\0';
}

int htspdf_extract_title(const char *html, size_t len,
                         char *title, size_t title_size) {
  title[0] = '\0';
  const char *t = ci_find(html, len, "<title");
  if (t) {
    const char *gt = memchr(t, '>', len - (size_t) (t - html));
    if (gt) {
      const char *e = ci_find(gt, len - (size_t) (gt - html), "</title>");
      if (e && e > gt + 1) {
        strip_collapse(gt + 1, (size_t) (e - gt - 1), title, title_size);
        if (title[0])
          return 1;
      }
    }
  }
  /* fallback: first <h1> */
  const char *h = ci_find(html, len, "<h1");
  if (h) {
    const char *gt = memchr(h, '>', len - (size_t) (h - html));
    if (gt) {
      const char *e = ci_find(gt, len - (size_t) (gt - html), "</h1>");
      if (e && e > gt + 1) {
        /* strip inner tags crudely */
        sbuf raw = { 0 };
        for (const char *p = gt + 1; p < e; p++) {
          if (*p == '<') { while (p < e && *p != '>') p++; }
          else sb_putc(&raw, *p);
        }
        if (raw.p) {
          strip_collapse(raw.p, raw.len, title, title_size);
          free(raw.p);
        }
        if (title[0])
          return 1;
      }
    }
  }
  return 0;
}

/* ============================================================ */
/*  Path helpers                                                 */
/* ============================================================ */

static int has_html_ext(const char *name) {
  size_t n = strlen(name);
  if (n > 5 && strcasecmp(name + n - 5, ".html") == 0) return 1;
  if (n > 4 && strcasecmp(name + n - 4, ".htm") == 0) return 1;
  return 0;
}

/* Make an absolute path (caller frees). */
static char *abs_path(const char *p) {
#ifdef _WIN32
  char buf[32768];
  if (_fullpath(buf, p, sizeof(buf)) != NULL)
    return strdup(buf);
  return strdup(p);
#else
  char buf[PATH_MAX];
  if (realpath(p, buf) != NULL)
    return strdup(buf);
  return strdup(p);
#endif
}

/* Replace .html/.htm extension with .pdf (caller frees). */
static char *to_pdf_path(const char *html) {
  size_t n = strlen(html);
  size_t base = n;
  if (n > 5 && strcasecmp(html + n - 5, ".html") == 0) base = n - 5;
  else if (n > 4 && strcasecmp(html + n - 4, ".htm") == 0) base = n - 4;
  char *out = (char *) malloc(base + 5);
  if (!out) return NULL;
  memcpy(out, html, base);
  strcpy(out + base, ".pdf");
  return out;
}

/* Build a file:// URL from an absolute path (caller frees). */
static char *file_url(const char *abs) {
  sbuf u = { 0 };
  sb_puts(&u, "file://");
#ifdef _WIN32
  sb_putc(&u, '/');             /* file:///C:/... */
#endif
  for (const char *p = abs; *p; p++) {
    unsigned char c = (unsigned char) *p;
    if (c == '\\') c = '/';
    if (c == ' ') { sb_puts(&u, "%20"); }
    else if (c == '#') { sb_puts(&u, "%23"); }
    else if (c == '?') { sb_puts(&u, "%3F"); }
    else if (c == '%') { sb_puts(&u, "%25"); }
    else sb_putc(&u, (char) c);
  }
  return u.p ? u.p : strdup("");
}

static char *read_file(const char *path, size_t *out_len, size_t cap) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  sbuf b = { 0 };
  char tmp[65536];
  size_t r;
  while ((r = fread(tmp, 1, sizeof(tmp), f)) > 0) {
    sb_putn(&b, tmp, r);
    if (cap && b.len >= cap) break;
  }
  fclose(f);
  if (out_len) *out_len = b.len;
  return b.p;
}

/* ============================================================ */
/*  Directory walk                                               */
/* ============================================================ */

typedef struct {
  char  **items;
  size_t  n, cap;
} strlist;

static void sl_add(strlist *l, const char *s) {
  if (l->n + 1 > l->cap) {
    l->cap = l->cap ? l->cap * 2 : 64;
    l->items = (char **) realloc(l->items, l->cap * sizeof(char *));
  }
  l->items[l->n++] = strdup(s);
}

static void sl_free(strlist *l) {
  for (size_t i = 0; i < l->n; i++)
    free(l->items[i]);
  free(l->items);
  l->items = NULL;
  l->n = l->cap = 0;
}

static void walk_dir(const char *root, strlist *out) {
#ifdef _WIN32
  char pattern[32768];
  snprintf(pattern, sizeof(pattern), "%s\\*", root);
  WIN32_FIND_DATAA fd;
  HANDLE h = FindFirstFileA(pattern, &fd);
  if (h == INVALID_HANDLE_VALUE)
    return;
  do {
    if (strcmp(fd.cFileName, ".") == 0 || strcmp(fd.cFileName, "..") == 0)
      continue;
    char full[32768];
    snprintf(full, sizeof(full), "%s\\%s", root, fd.cFileName);
    if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
      walk_dir(full, out);
    } else if (has_html_ext(fd.cFileName)) {
      sl_add(out, full);
    }
  } while (FindNextFileA(h, &fd));
  FindClose(h);
#else
  DIR *d = opendir(root);
  if (!d)
    return;
  struct dirent *de;
  while ((de = readdir(d)) != NULL) {
    if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0)
      continue;
    char full[8192];
    snprintf(full, sizeof(full), "%s/%s", root, de->d_name);
    struct stat st;
    if (stat(full, &st) != 0)
      continue;
    if (S_ISDIR(st.st_mode))
      walk_dir(full, out);
    else if (S_ISREG(st.st_mode) && has_html_ext(de->d_name))
      sl_add(out, full);
  }
  closedir(d);
#endif
}

/* ============================================================ */
/*  Browser / Ghostscript detection                             */
/* ============================================================ */

static int file_executable(const char *p) {
#ifdef _WIN32
  return GetFileAttributesA(p) != INVALID_FILE_ATTRIBUTES;
#else
  return access(p, X_OK) == 0;
#endif
}

/* Search PATH for `prog` (caller frees result, or NULL). */
static char *which(const char *prog) {
  const char *path = getenv("PATH");
  if (!path) return NULL;
#ifdef _WIN32
  char sep = ';';
#else
  char sep = ':';
#endif
  char *copy = strdup(path);
  char *p = copy, *next;
  char *found = NULL;
  while (p && *p) {
    next = strchr(p, sep);
    if (next) *next = '\0';
    char cand[4096];
    snprintf(cand, sizeof(cand), "%s%c%s", p, HTSPDF_PATHSEP, prog);
    if (file_executable(cand)) { found = strdup(cand); break; }
    if (!next) break;
    p = next + 1;
  }
  free(copy);
  return found;
}

static int detect_browser(const htspdf_config *cfg, char *out, size_t cap) {
  if (cfg->chrome_path[0]) {
    strncpy(out, cfg->chrome_path, cap - 1);
    out[cap - 1] = '\0';
    return 1;
  }
#ifdef _WIN32
  const char *cands[] = {
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    NULL
  };
  for (int i = 0; cands[i]; i++)
    if (file_executable(cands[i])) { strncpy(out, cands[i], cap - 1); out[cap-1]=0; return 1; }
  const char *lad = getenv("LOCALAPPDATA");
  if (lad) {
    char c[4096];
    snprintf(c, sizeof(c), "%s\\Google\\Chrome\\Application\\chrome.exe", lad);
    if (file_executable(c)) { strncpy(out, c, cap - 1); out[cap-1]=0; return 1; }
  }
  const char *names[] = { "chrome.exe", "msedge.exe", NULL };
#else
  const char *names[] = {
    "google-chrome", "google-chrome-stable", "chromium",
    "chromium-browser", "chrome", NULL
  };
#endif
  for (int i = 0; names[i]; i++) {
    char *w = which(names[i]);
    if (w) { strncpy(out, w, cap - 1); out[cap-1]=0; free(w); return 1; }
  }
  return 0;
}

static int detect_ghostscript(const htspdf_config *cfg, char *out, size_t cap) {
  if (cfg->gs_path[0]) {
    strncpy(out, cfg->gs_path, cap - 1); out[cap-1]=0; return 1;
  }
#ifdef _WIN32
  const char *names[] = { "gswin64c.exe", "gswin32c.exe", "gs.exe", NULL };
#else
  const char *names[] = { "gs", "ghostscript", NULL };
#endif
  for (int i = 0; names[i]; i++) {
    char *w = which(names[i]);
    if (w) { strncpy(out, w, cap - 1); out[cap-1]=0; free(w); return 1; }
  }
  return 0;
}

/* ============================================================ */
/*  Logging                                                      */
/* ============================================================ */

typedef struct {
  FILE *fp;
  int   errors;
  int   ok;
} htspdf_log;

static void log_open(htspdf_log *lg, const char *root) {
  char path[8192];
  snprintf(path, sizeof(path), "%s%cpdf_errors.log", root, HTSPDF_PATHSEP);
  lg->fp = fopen(path, "wb");
  lg->errors = lg->ok = 0;
}

static void log_msg(htspdf_log *lg, const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  if (lg->fp) {
    va_list cp;
    va_copy(cp, ap);
    vfprintf(lg->fp, fmt, cp);
    fputc('\n', lg->fp);
    fflush(lg->fp);
    va_end(cp);
  }
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
}

static void log_close(htspdf_log *lg) {
  if (lg->fp) fclose(lg->fp);
  lg->fp = NULL;
}

/* ============================================================ */
/*  Browser invocation with timeout and concurrency             */
/* ============================================================ */

typedef struct {
  char *html;                   /* source html (absolute)         */
  char *pdf;                    /* destination pdf (absolute)     */
  char  title[512];             /* for the merge bookmark TOC     */
  int   status;                 /* 0=pending 1=ok 2=fail 3=timeout */
} htspdf_task;

/* Build the argv vector for one conversion (NULL terminated, caller frees
   the dynamic members and the array). `slot` selects a private profile dir. */
static char **build_argv(const char *browser, const htspdf_task *t,
                         const htspdf_config *cfg, int slot,
                         const char *root) {
  char *url = file_url(t->html);
  char *p_pdf = (char *) malloc(strlen(t->pdf) + 32);
  sprintf(p_pdf, "--print-to-pdf=%s", t->pdf);
  char *profile = (char *) malloc(strlen(root) + 64);
  sprintf(profile, "--user-data-dir=%s%c.htspdf_tmp%c%d", root,
          HTSPDF_PATHSEP, HTSPDF_PATHSEP, slot);

  const char *fixed[] = {
    "--headless=new", "--disable-gpu", "--no-sandbox",
    "--no-first-run", "--no-default-browser-check", "--disable-extensions",
    "--disable-dev-shm-usage", "--hide-scrollbars",
    "--allow-file-access-from-files",
    "--run-all-compositor-stages-before-draw",
    "--virtual-time-budget=15000",
    "--print-to-pdf-no-header",
  };
  int nfixed = (int) (sizeof(fixed) / sizeof(fixed[0]));
  int n = 0;
  char **argv = (char **) calloc(nfixed + 5, sizeof(char *));
  argv[n++] = strdup(browser);
  for (int i = 0; i < nfixed; i++)
    argv[n++] = strdup(fixed[i]);
  argv[n++] = profile;          /* already malloc'd */
  argv[n++] = p_pdf;            /* already malloc'd */
  argv[n++] = url;              /* already malloc'd */
  argv[n] = NULL;
  return argv;
}

static void free_argv(char **argv) {
  for (int i = 0; argv && argv[i]; i++)
    free(argv[i]);
  free(argv);
}

#ifdef _WIN32
/* ---- Windows concurrent pool (CreateProcess + WaitForMultipleObjects) ---- */

static void quote_arg(sbuf *cmd, const char *a) {
  int need = (strchr(a, ' ') || strchr(a, '\t') || *a == '\0');
  if (need) sb_putc(cmd, '"');
  for (const char *p = a; *p; p++) {
    if (*p == '"') sb_putc(cmd, '\\');
    sb_putc(cmd, *p);
  }
  if (need) sb_putc(cmd, '"');
}

static int run_pool(htspdf_task *tasks, int count, const char *browser,
                    const htspdf_config *cfg, const char *root,
                    htspdf_log *lg) {
  int conc = cfg->concurrency;
  HANDLE *handles = (HANDLE *) calloc(conc, sizeof(HANDLE));
  int    *slotIdx = (int *) calloc(conc, sizeof(int));
  DWORD  *started = (DWORD *) calloc(conc, sizeof(DWORD));
  int running = 0, next = 0, done = 0, okc = 0;

  while (done < count) {
    while (running < conc && next < count) {
      int slot = -1;
      for (int k = 0; k < conc; k++) if (handles[k] == NULL) { slot = k; break; }
      char **argv = build_argv(browser, &tasks[next], cfg, slot, root);
      sbuf cmd = { 0 };
      for (int i = 0; argv[i]; i++) { if (i) sb_putc(&cmd, ' '); quote_arg(&cmd, argv[i]); }

      STARTUPINFOA si; PROCESS_INFORMATION pi;
      ZeroMemory(&si, sizeof(si)); si.cb = sizeof(si);
      ZeroMemory(&pi, sizeof(pi));
      BOOL ok = CreateProcessA(NULL, cmd.p, NULL, NULL, FALSE,
                               CREATE_NO_WINDOW, NULL, NULL, &si, &pi);
      free(cmd.p);
      free_argv(argv);
      if (!ok) {
        log_msg(lg, "[error] cannot launch browser for %s (err=%lu)",
                tasks[next].html, (unsigned long) GetLastError());
        tasks[next].status = 2; lg->errors++; done++; next++;
        continue;
      }
      CloseHandle(pi.hThread);
      handles[slot] = pi.hProcess;
      slotIdx[slot] = next;
      started[slot] = GetTickCount();
      running++; next++;
    }
    if (running == 0) break;

    /* compact handle list for WaitForMultipleObjects */
    HANDLE wait[64]; int map[64], wn = 0;
    for (int k = 0; k < conc && wn < 64; k++)
      if (handles[k]) { wait[wn] = handles[k]; map[wn] = k; wn++; }

    DWORD w = WaitForMultipleObjects(wn, wait, FALSE, 250);
    if (w >= WAIT_OBJECT_0 && w < WAIT_OBJECT_0 + (DWORD) wn) {
      int slot = map[w - WAIT_OBJECT_0];
      DWORD code = 1;
      GetExitCodeProcess(handles[slot], &code);
      int idx = slotIdx[slot];
      if (code == 0) { tasks[idx].status = 1; okc++; lg->ok++; }
      else { tasks[idx].status = 2; lg->errors++;
             log_msg(lg, "[error] browser exit=%lu for %s",
                     (unsigned long) code, tasks[idx].html); }
      CloseHandle(handles[slot]); handles[slot] = NULL;
      running--; done++;
    }
    /* enforce per-file timeout */
    DWORD now = GetTickCount();
    for (int k = 0; k < conc; k++) {
      if (handles[k] &&
          now - started[k] > (DWORD) cfg->timeout_sec * 1000) {
        int idx = slotIdx[k];
        TerminateProcess(handles[k], 1);
        WaitForSingleObject(handles[k], 2000);
        CloseHandle(handles[k]); handles[k] = NULL;
        tasks[idx].status = 3; lg->errors++; running--; done++;
        log_msg(lg, "[timeout] %s (> %ds)", tasks[idx].html, cfg->timeout_sec);
      }
    }
  }
  free(handles); free(slotIdx); free(started);
  return okc;
}

#else
/* ---- POSIX concurrent pool (fork/exec + waitpid) ---- */

static pid_t spawn(char **argv) {
  pid_t pid = fork();
  if (pid == 0) {
    int dn = open("/dev/null", O_WRONLY);
    if (dn >= 0) { dup2(dn, 1); dup2(dn, 2); close(dn); }
    execvp(argv[0], argv);
    _exit(127);
  }
  return pid;
}

static int run_pool(htspdf_task *tasks, int count, const char *browser,
                    const htspdf_config *cfg, const char *root,
                    htspdf_log *lg) {
  int conc = cfg->concurrency;
  pid_t  *pids = (pid_t *) calloc(conc, sizeof(pid_t));
  int    *slotIdx = (int *) calloc(conc, sizeof(int));
  time_t *started = (time_t *) calloc(conc, sizeof(time_t));
  int running = 0, next = 0, done = 0, okc = 0;

  while (done < count) {
    while (running < conc && next < count) {
      int slot = -1;
      for (int k = 0; k < conc; k++) if (pids[k] == 0) { slot = k; break; }
      char **argv = build_argv(browser, &tasks[next], cfg, slot, root);
      pid_t pid = spawn(argv);
      free_argv(argv);
      if (pid <= 0) {
        log_msg(lg, "[error] cannot fork for %s", tasks[next].html);
        tasks[next].status = 2; lg->errors++; done++; next++;
        continue;
      }
      pids[slot] = pid; slotIdx[slot] = next; started[slot] = time(NULL);
      running++; next++;
    }
    if (running == 0) break;

    int status;
    pid_t fin = waitpid(-1, &status, WNOHANG);
    if (fin > 0) {
      for (int k = 0; k < conc; k++) {
        if (pids[k] == fin) {
          int idx = slotIdx[k];
          int code = WIFEXITED(status) ? WEXITSTATUS(status) : 1;
          if (code == 0) { tasks[idx].status = 1; okc++; lg->ok++; }
          else { tasks[idx].status = 2; lg->errors++;
                 log_msg(lg, "[error] browser exit=%d for %s", code,
                         tasks[idx].html); }
          pids[k] = 0; running--; done++;
          break;
        }
      }
    } else {
      struct timespec ts = { 0, 50 * 1000000 };
      nanosleep(&ts, NULL);
    }
    /* enforce timeout */
    time_t now = time(NULL);
    for (int k = 0; k < conc; k++) {
      if (pids[k] != 0 && now - started[k] > cfg->timeout_sec) {
        int idx = slotIdx[k];
        kill(pids[k], SIGKILL);
        waitpid(pids[k], NULL, 0);
        tasks[idx].status = 3; lg->errors++;
        pids[k] = 0; running--; done++;
        log_msg(lg, "[timeout] %s (> %ds)", tasks[idx].html, cfg->timeout_sec);
      }
    }
  }
  free(pids); free(slotIdx); free(started);
  return okc;
}
#endif

/* ============================================================ */
/*  PDF merge via Ghostscript (with bookmark TOC)               */
/* ============================================================ */

/* UTF-8 -> UTF-16BE hex string for a pdfmark Title (handles Cyrillic). */
static void title_to_pdfmark_hex(const char *utf8, sbuf *out) {
  sb_puts(out, "<FEFF");
  const unsigned char *p = (const unsigned char *) utf8;
  while (*p) {
    unsigned int cp = 0, n = 0;
    if (*p < 0x80) { cp = *p; n = 0; }
    else if ((*p >> 5) == 0x6) { cp = *p & 0x1F; n = 1; }
    else if ((*p >> 4) == 0xE) { cp = *p & 0x0F; n = 2; }
    else if ((*p >> 3) == 0x1E) { cp = *p & 0x07; n = 3; }
    else { cp = '?'; n = 0; }
    p++;
    for (unsigned int i = 0; i < n && *p; i++) { cp = (cp << 6) | (*p & 0x3F); p++; }
    char hex[16];
    if (cp <= 0xFFFF) {
      snprintf(hex, sizeof(hex), "%04X", cp);
      sb_puts(out, hex);
    } else {                    /* surrogate pair */
      cp -= 0x10000;
      snprintf(hex, sizeof(hex), "%04X%04X",
               0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
      sb_puts(out, hex);
    }
  }
  sb_putc(out, '>');
}

/* Get page count of a PDF by asking Ghostscript. Returns -1 on failure. */
static int pdf_page_count(const char *gs, const char *pdf) {
  sbuf cmd = { 0 };
  sb_putc(&cmd, '"'); sb_puts(&cmd, gs); sb_putc(&cmd, '"');
  sb_puts(&cmd, " -q -dNODISPLAY -dNOSAFER -c \"(");
  sb_puts(&cmd, pdf);
  sb_puts(&cmd, ") (r) file runpdfbegin pdfpagecount = quit\"");
  FILE *fp = popen(cmd.p, "r");
  free(cmd.p);
  if (!fp) return -1;
  char buf[64]; int pages = -1;
  if (fgets(buf, sizeof(buf), fp)) pages = atoi(buf);
  pclose(fp);
  return pages;
}

static int merge_pdfs(htspdf_task *tasks, int count, const htspdf_config *cfg,
                      const char *root, htspdf_log *lg) {
  char gs[1024];
  if (!detect_ghostscript(cfg, gs, sizeof(gs))) {
    log_msg(lg, "[warn] Ghostscript not found - skipping --pdf-merge "
                "(install ghostscript, or set gs=<path>)");
    return 0;
  }

  /* gather successful PDFs in deterministic order */
  char marks_path[8192], list_path[8192], out_path[8192];
  snprintf(marks_path, sizeof(marks_path), "%s%c.htspdf_marks.ps", root, HTSPDF_PATHSEP);
  snprintf(list_path, sizeof(list_path), "%s%c.htspdf_list.txt", root, HTSPDF_PATHSEP);
  if (cfg->merge_file[0])
    snprintf(out_path, sizeof(out_path), "%s", cfg->merge_file);
  else
    snprintf(out_path, sizeof(out_path), "%s%cbook.pdf", root, HTSPDF_PATHSEP);

  FILE *marks = fopen(marks_path, "wb");
  FILE *list  = fopen(list_path, "wb");
  if (!marks || !list) {
    if (marks) fclose(marks);
    if (list) fclose(list);
    log_msg(lg, "[error] cannot write merge work files in %s", root);
    return 0;
  }
  /* marks.ps must be the first input so /Page refers to output pages */
  fprintf(list, "%s\n", marks_path);

  int page = 1, merged = 0;
  for (int i = 0; i < count; i++) {
    if (tasks[i].status != 1)
      continue;
    int pc = pdf_page_count(gs, tasks[i].pdf);
    if (pc <= 0) pc = 1;        /* be forgiving */
    sbuf hex = { 0 };
    title_to_pdfmark_hex(tasks[i].title[0] ? tasks[i].title : tasks[i].pdf, &hex);
    fprintf(marks, "[ /Page %d /Title %s /OUT pdfmark\n", page, hex.p);
    free(hex.p);
    fprintf(list, "%s\n", tasks[i].pdf);
    page += pc;
    merged++;
  }
  fclose(marks);
  fclose(list);

  if (merged == 0) {
    log_msg(lg, "[warn] nothing to merge");
    return 0;
  }

  sbuf cmd = { 0 };
  sb_putc(&cmd, '"'); sb_puts(&cmd, gs); sb_putc(&cmd, '"');
  sb_puts(&cmd, " -dBATCH -dNOPAUSE -q -sDEVICE=pdfwrite -dPDFSETTINGS=/prepress");
  sb_puts(&cmd, " -sOutputFile=\""); sb_puts(&cmd, out_path); sb_putc(&cmd, '"');
  sb_puts(&cmd, " \"@"); sb_puts(&cmd, list_path); sb_putc(&cmd, '"');

  int rc = system(cmd.p);
  free(cmd.p);
  remove(marks_path);
  remove(list_path);

  if (rc != 0) {
    log_msg(lg, "[error] Ghostscript merge failed (rc=%d)", rc);
    return 0;
  }
  log_msg(lg, "[ok] merged %d PDF(s) -> %s", merged, out_path);
  return merged;
}

/* ============================================================ */
/*  Public: export an entire directory                          */
/* ============================================================ */

int htspdf_export_dir(const char *root, const htspdf_config *cfg) {
  htspdf_log lg;
  log_open(&lg, root);

  char browser[1024];
  if (!detect_browser(cfg, browser, sizeof(browser))) {
    log_msg(&lg, "[warn] no Chrome/Chromium/Edge found - PDF export skipped. "
                 "Install Chrome or set chrome=<path>.");
    log_close(&lg);
    return 0;
  }
  log_msg(&lg, "[info] browser: %s", browser);

  strlist files = { 0 };
  walk_dir(root, &files);
  log_msg(&lg, "[info] found %zu HTML file(s) under %s", files.n, root);
  if (files.n == 0) { sl_free(&files); log_close(&lg); return 0; }

  htspdf_task *tasks = (htspdf_task *) calloc(files.n, sizeof(htspdf_task));
  int count = 0;
  for (size_t i = 0; i < files.n; i++) {
    char *ap = abs_path(files.items[i]);
    char *pdf = to_pdf_path(ap);
    if (!ap || !pdf) { free(ap); free(pdf); continue; }
    tasks[count].html = ap;
    tasks[count].pdf  = pdf;
    /* derive a bookmark title from the (possibly cleaned) HTML */
    size_t hl = 0;
    char *html = read_file(ap, &hl, 512 * 1024);
    if (html) {
      if (!htspdf_extract_title(html, hl, tasks[count].title,
                                sizeof(tasks[count].title))) {
        /* fall back to filename without extension */
        const char *b = strrchr(files.items[i], HTSPDF_PATHSEP);
        b = b ? b + 1 : files.items[i];
        strncpy(tasks[count].title, b, sizeof(tasks[count].title) - 1);
      }
      free(html);
    }
    count++;
  }

  time_t t0 = time(NULL);
  int ok = run_pool(tasks, count, browser, cfg, root, &lg);
  time_t t1 = time(NULL);

  log_msg(&lg, "[info] converted %d/%d file(s) in %lds (%d error/timeout)",
          ok, count, (long) (t1 - t0), lg.errors);

  if (cfg->do_merge && ok > 0)
    merge_pdfs(tasks, count, cfg, root, &lg);

  for (int i = 0; i < count; i++) { free(tasks[i].html); free(tasks[i].pdf); }
  free(tasks);
  sl_free(&files);
  log_close(&lg);
  return ok;
}

/* ============================================================ */
/*  HTTrack glue                                                 */
/* ============================================================ */

#ifndef HTSPDF_STANDALONE

#include "httrack-library.h"
#include "htsopt.h"
#include "htsdefines.h"
#include "htsstrings.h"         /* StringBuff() */

/* one shared configuration, freed by the end callback */
static int pdf_postprocess(t_hts_callbackarg *carg, httrackp *opt,
                           char **html, int *len, const char *url_address,
                           const char *url_file);
static int pdf_end(t_hts_callbackarg *carg, httrackp *opt);

int httrack_pdf_init(struct httrackp *opt, const char *args) {
  htspdf_config *cfg = (htspdf_config *) malloc(sizeof(htspdf_config));
  if (!cfg)
    return 0;
  htspdf_config_parse(cfg, args);

  /* clean HTML on the fly (preserves verstka, drops ads/JS) */
  CHAIN_FUNCTION(opt, postprocess, pdf_postprocess, cfg);
  /* convert + merge once the mirror is complete */
  CHAIN_FUNCTION(opt, end, pdf_end, cfg);
  return 1;
}

/* The HTTrack postprocess-html callback. *html is an hts_malloc()'d buffer. */
static int pdf_postprocess(t_hts_callbackarg *carg, httrackp *opt,
                           char **html, int *len, const char *url_address,
                           const char *url_file) {
  htspdf_config *cfg = (htspdf_config *) CALLBACKARG_USERDEF(carg);

  /* preserve the callback chain */
  if (CALLBACKARG_PREV_FUN(carg, postprocess) != NULL) {
    CALLBACKARG_PREV_FUN(carg, postprocess)
      (CALLBACKARG_PREV_CARG(carg), opt, html, len, url_address, url_file);
  }

  if (cfg == NULL || cfg->clean == HTSPDF_CLEAN_OFF || *html == NULL)
    return 1;

  size_t nl = 0;
  char *cleaned = htspdf_clean_html(*html, (size_t) (*len), cfg, &nl);
  if (cleaned != NULL) {
    /* hand a fresh buffer back to HTTrack (free the old one) */
    char *dup = hts_strdup(cleaned);
    free(cleaned);
    if (dup != NULL) {
      hts_free(*html);
      *html = dup;
      *len = (int) nl;
    }
  }
  return 1;
}

/* The HTTrack end callback: runs after the whole mirror is finished. */
static int pdf_end(t_hts_callbackarg *carg, httrackp *opt) {
  htspdf_config *cfg = (htspdf_config *) CALLBACKARG_USERDEF(carg);

  if (cfg != NULL && cfg->enabled) {
    const char *root = cfg->out_dir[0] ? cfg->out_dir
                                       : StringBuff(opt->path_html);
    if (root && *root) {
      fprintf(stderr, "\n** HTTrack PDF export starting in: %s\n", root);
      htspdf_export_dir(root, cfg);
    } else {
      fprintf(stderr, "** HTTrack PDF export: output path unknown, skipped\n");
    }
  }
  if (cfg != NULL)
    free(cfg);

  /* preserve the callback chain */
  if (CALLBACKARG_PREV_FUN(carg, end) != NULL)
    return CALLBACKARG_PREV_FUN(carg, end) (CALLBACKARG_PREV_CARG(carg), opt);
  return 1;
}

/* ---- external wrapper entry points (when built as .so/.dll) ---- */
EXTERNAL_FUNCTION int hts_plug(httrackp *opt, const char *argv);
EXTERNAL_FUNCTION int hts_unplug(httrackp *opt);

EXTERNAL_FUNCTION int hts_plug(httrackp *opt, const char *argv) {
  const char *arg = (argv != NULL) ? strchr(argv, ',') : NULL;
  if (arg != NULL)
    arg++;                      /* skip module name */
  return httrack_pdf_init(opt, arg);
}

EXTERNAL_FUNCTION int hts_unplug(httrackp *opt) {
  (void) opt;
  return 1;
}

#endif /* !HTSPDF_STANDALONE */

/* ============================================================ */
/*  Standalone CLI (test harness on an existing mirror)         */
/* ============================================================ */

#ifdef HTSPDF_STANDALONE

static void usage(const char *p) {
  fprintf(stderr,
    "httrack_pdf (standalone) - convert a downloaded mirror to PDF\n"
    "Usage: %s <dir> [export,merge,clean=lj,pagesize=A4,concurrency=4,...]\n"
    "Options (comma separated):\n"
    "  export            do the conversion (default on)\n"
    "  merge             merge all PDFs into <dir>/book.pdf (needs ghostscript)\n"
    "  clean=lj|generic|off   HTML cleaning strategy\n"
    "  noimg             strip images\n"
    "  pagesize=A4       page format\n"
    "  concurrency=N     parallel browsers (1..8)\n"
    "  timeout=N         per-file timeout seconds\n"
    "  chrome=<path>     explicit browser path\n"
    "  gs=<path>         explicit ghostscript path\n"
    "  clean-also: when cleaning, *.html are rewritten in place first\n",
    p);
}

int main(int argc, char **argv) {
  if (argc < 2) { usage(argv[0]); return 2; }
  htspdf_config cfg;
  htspdf_config_parse(&cfg, argc >= 3 ? argv[2] : "export");

  const char *dir = argv[1];

  /* If cleaning is requested, rewrite each HTML in place before printing,
     so the standalone tool mirrors what the HTTrack callback would do. */
  if (cfg.clean != HTSPDF_CLEAN_OFF) {
    strlist files = { 0 };
    walk_dir(dir, &files);
    for (size_t i = 0; i < files.n; i++) {
      size_t hl = 0;
      char *html = read_file(files.items[i], &hl, 0);
      if (!html) continue;
      size_t nl = 0;
      char *clean = htspdf_clean_html(html, hl, &cfg, &nl);
      if (clean) {
        FILE *f = fopen(files.items[i], "wb");
        if (f) { fwrite(clean, 1, nl, f); fclose(f); }
        free(clean);
      }
      free(html);
    }
    sl_free(&files);
  }

  int ok = htspdf_export_dir(dir, &cfg);
  fprintf(stderr, "Done. %d file(s) converted.\n", ok);
  return ok > 0 ? 0 : 1;
}
#endif /* HTSPDF_STANDALONE */
