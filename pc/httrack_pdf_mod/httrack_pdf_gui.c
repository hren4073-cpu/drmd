/* httrack_pdf_gui.c - Win32 GUI front-end for httrack2pdf.
 *
 * Two workflows in one window:
 *   1) "LiveJournal book": type a blog URL + page range, hit Start, and the
 *      tool downloads every page, renders each to PDF and merges them into a
 *      single book.pdf with a clickable table of contents.
 *   2) "Local folder": point it at an already-downloaded HTTrack mirror and
 *      convert/merge that instead.
 *
 * Compile (MinGW, static, no console window):
 *   gcc -O2 -std=gnu99 -DHTSPDF_STANDALONE -DHTSPDF_GUI \
 *       -static -static-libgcc -s -mwindows \
 *       -o httrack2pdf.exe httrack_pdf_gui.c httrack_pdf.c \
 *       -lcomdlg32 -lshell32 -lole32 -lcomctl32
 */

#ifndef _WIN32
#  error "httrack_pdf_gui.c is Windows-only."
#endif

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <commctrl.h>
#include <shlobj.h>
#include <commdlg.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "httrack_pdf.h"

/* ── Control IDs ─────────────────────────────────────────────────────────── */
#define IDC_GRP_LJ        200
#define IDC_CHK_LJ        201
#define IDC_LBL_LJURL     202
#define IDC_EDT_LJURL     203
#define IDC_LBL_LJFROM    204
#define IDC_EDT_LJFROM    205
#define IDC_LBL_LJTO      206
#define IDC_EDT_LJTO      207
#define IDC_LBL_LJSTEP    208
#define IDC_EDT_LJSTEP    209

#define IDC_LBL_MIRROR    100
#define IDC_EDT_MIRROR    101
#define IDC_BTN_MIRROR    102
#define IDC_GRP_OPT       110
#define IDC_CHK_EXPORT    111
#define IDC_CHK_MERGE     112
#define IDC_CHK_COMMENTS  113
#define IDC_CHK_NOIMAGES  114
#define IDC_LBL_CLEAN     115
#define IDC_CMB_CLEAN     116
#define IDC_LBL_PAGE      117
#define IDC_CMB_PAGE      118
#define IDC_LBL_CONC      119
#define IDC_EDT_CONC      120
#define IDC_LBL_TIMEOUT   121
#define IDC_EDT_TIMEOUT   122
#define IDC_GRP_ADV       130
#define IDC_LBL_CHROME    131
#define IDC_EDT_CHROME    132
#define IDC_BTN_CHROME    133
#define IDC_LBL_GS        134
#define IDC_EDT_GS        135
#define IDC_BTN_GS        136
#define IDC_BTN_START     140
#define IDC_BTN_CANCEL    141
#define IDC_LBL_STATUS    142
#define IDC_EDT_LOG       150

/* Custom messages (WM_APP range is safe for in-process use). */
#define WM_LOG_LINE   (WM_APP + 1)   /* lParam = malloc'd char*; caller frees */
#define WM_WORK_DONE  (WM_APP + 2)   /* wParam = number of files converted    */

/* Where the log panel starts (used by WM_CREATE and WM_SIZE). */
#define LOG_TOP   478

/* ── Global state ────────────────────────────────────────────────────────── */
static HWND   g_hwnd    = NULL;
static HANDLE g_thread  = NULL;
static volatile int g_running = 0;

/* ── Log callback (called from the worker thread) ────────────────────────── */
static void gui_log(const char *line, void *ud)
{
    (void)ud;
    if (!g_hwnd) return;
    char *copy = _strdup(line);
    if (copy)
        PostMessage(g_hwnd, WM_LOG_LINE, 0, (LPARAM)copy);
}

/* ── Worker thread ───────────────────────────────────────────────────────── */
typedef struct {
    int           lj_mode;           /* 1 = LiveJournal book, 0 = local folder */
    char          mirror[MAX_PATH];  /* folder (local mode) / workdir (lj mode)*/
    char          lj_url[2048];
    int           lj_from, lj_to, lj_step;
    htspdf_config cfg;
} WorkArgs;

static DWORD WINAPI worker_proc(LPVOID p)
{
    WorkArgs *a = (WorkArgs *)p;
    int ok;
    if (a->lj_mode) {
        ok = htspdf_lj_book(a->lj_url, a->lj_from, a->lj_to, a->lj_step,
                            a->mirror, &a->cfg);
    } else {
        const char *root = (a->cfg.out_dir[0]) ? a->cfg.out_dir : a->mirror;
        ok = htspdf_export_dir(root, &a->cfg);
    }
    PostMessage(g_hwnd, WM_WORK_DONE, (WPARAM)ok, 0);
    free(a);
    return 0;
}

/* ── Helpers ─────────────────────────────────────────────────────────────── */

static void log_append(HWND hlog, const char *text)
{
    int len = GetWindowTextLengthA(hlog);
    SendMessage(hlog, EM_SETSEL, (WPARAM)len, (LPARAM)len);
    SendMessage(hlog, EM_REPLACESEL, FALSE, (LPARAM)text);
    SendMessage(hlog, EM_REPLACESEL, FALSE, (LPARAM)"\r\n");
    SendMessage(hlog, WM_VSCROLL, SB_BOTTOM, 0);
}

static void set_status(HWND hwnd, const char *msg)
{
    SetDlgItemTextA(hwnd, IDC_LBL_STATUS, msg);
}

static int browse_folder(HWND parent, const char *title, char *out, int cap)
{
    BROWSEINFOA bi;
    ZeroMemory(&bi, sizeof(bi));
    bi.hwndOwner = parent;
    bi.lpszTitle = title;
    bi.ulFlags   = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
    LPITEMIDLIST pidl = SHBrowseForFolderA(&bi);
    if (!pidl) return 0;
    int ok = SHGetPathFromIDListA(pidl, out);
    CoTaskMemFree(pidl);
    (void)cap;
    return ok;
}

static int browse_exe(HWND parent, const char *title, char *out, int cap)
{
    OPENFILENAMEA ofn;
    ZeroMemory(&ofn, sizeof(ofn));
    out[0] = '\0';
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner   = parent;
    ofn.lpstrTitle  = title;
    ofn.lpstrFile   = out;
    ofn.nMaxFile    = (DWORD)cap;
    ofn.lpstrFilter = "Executables\0*.exe\0All files\0*.*\0";
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST;
    return GetOpenFileNameA(&ofn);
}

/* Enable/disable LJ vs folder controls when the mode checkbox toggles. */
static void apply_mode(HWND hwnd)
{
    int lj = (IsDlgButtonChecked(hwnd, IDC_CHK_LJ) == BST_CHECKED);
    EnableWindow(GetDlgItem(hwnd, IDC_EDT_LJURL),  lj);
    EnableWindow(GetDlgItem(hwnd, IDC_EDT_LJFROM), lj);
    EnableWindow(GetDlgItem(hwnd, IDC_EDT_LJTO),   lj);
    EnableWindow(GetDlgItem(hwnd, IDC_EDT_LJSTEP), lj);
    /* In LJ mode the folder field becomes an optional output dir. */
    SetDlgItemTextA(hwnd, IDC_LBL_MIRROR,
                    lj ? "Output folder:" : "Mirror folder:");
}

/* Collect the shared PDF options into *cfg. */
static void read_cfg(HWND hwnd, htspdf_config *cfg)
{
    htspdf_config_defaults(cfg);

    cfg->enabled       = (IsDlgButtonChecked(hwnd, IDC_CHK_EXPORT)   == BST_CHECKED);
    cfg->do_merge      = (IsDlgButtonChecked(hwnd, IDC_CHK_MERGE)    == BST_CHECKED);
    cfg->keep_comments = (IsDlgButtonChecked(hwnd, IDC_CHK_COMMENTS) == BST_CHECKED);
    cfg->no_images     = (IsDlgButtonChecked(hwnd, IDC_CHK_NOIMAGES) == BST_CHECKED);

    int ci = (int)SendDlgItemMessage(hwnd, IDC_CMB_CLEAN, CB_GETCURSEL, 0, 0);
    if (ci < 0 || ci > 2) ci = 1;
    cfg->clean = (htspdf_clean_mode)ci;

    static const char *page_names[] = { "A4", "Letter", "Legal", "A3" };
    int pi = (int)SendDlgItemMessage(hwnd, IDC_CMB_PAGE, CB_GETCURSEL, 0, 0);
    if (pi < 0 || pi > 3) pi = 0;
    strncpy(cfg->page_size, page_names[pi], sizeof(cfg->page_size) - 1);

    char buf[64];
    GetDlgItemTextA(hwnd, IDC_EDT_CONC, buf, sizeof(buf));
    cfg->concurrency = atoi(buf);
    if (cfg->concurrency < 1) cfg->concurrency = 1;
    if (cfg->concurrency > 8) cfg->concurrency = 8;

    GetDlgItemTextA(hwnd, IDC_EDT_TIMEOUT, buf, sizeof(buf));
    cfg->timeout_sec = atoi(buf);
    if (cfg->timeout_sec < 5) cfg->timeout_sec = 5;

    GetDlgItemTextA(hwnd, IDC_EDT_CHROME, cfg->chrome_path, sizeof(cfg->chrome_path));
    GetDlgItemTextA(hwnd, IDC_EDT_GS,     cfg->gs_path,     sizeof(cfg->gs_path));
}

/* ── Control creation macro ──────────────────────────────────────────────── */
#define MK(cls, text, id, x, y, w, h, style) \
    do { \
        HWND _h = CreateWindowExA(0, (cls), (text), \
            WS_CHILD|WS_VISIBLE|(style), (x),(y),(w),(h), \
            hwnd, (HMENU)(UINT_PTR)(id), hInst, NULL); \
        SendMessage(_h, WM_SETFONT, (WPARAM)hFont, TRUE); \
    } while(0)

#define MKHINT(text, x, y, w) \
    do { \
        HWND _h = CreateWindowExA(0,"STATIC",(text), \
            WS_CHILD|WS_VISIBLE|SS_LEFT, (x),(y),(w),16, hwnd,(HMENU)0,hInst,NULL); \
        SendMessage(_h, WM_SETFONT, (WPARAM)hFont, TRUE); \
    } while(0)

/* ── Window procedure ────────────────────────────────────────────────────── */
static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    static HFONT hFont = NULL;
    HINSTANCE hInst = (HINSTANCE)GetWindowLongPtrA(hwnd, GWLP_HINSTANCE);

    switch (msg) {

    case WM_CREATE: {
        hFont = (HFONT)GetStockObject(DEFAULT_GUI_FONT);

        /* ── Group: LiveJournal book ─────────────────────────────────────── */
        MK("BUTTON", "LiveJournal blog -> PDF book", IDC_GRP_LJ,
           8, 6, 576, 118, BS_GROUPBOX);
        MK("BUTTON", "Download a blog and build a book (with table of contents)",
           IDC_CHK_LJ, 18, 24, 420, 20, BS_AUTOCHECKBOX);

        MK("STATIC", "Blog URL:", IDC_LBL_LJURL, 18, 52, 70, 18, SS_LEFT);
        MK("EDIT", "https://", IDC_EDT_LJURL, 92, 50, 478, 22,
           WS_BORDER|ES_AUTOHSCROLL);

        MK("STATIC", "From page:", IDC_LBL_LJFROM, 18, 82, 70, 18, SS_LEFT);
        MK("EDIT", "1", IDC_EDT_LJFROM, 92, 80, 50, 22, WS_BORDER|ES_NUMBER);
        MK("STATIC", "To page:", IDC_LBL_LJTO, 160, 82, 55, 18, SS_LEFT);
        MK("EDIT", "10", IDC_EDT_LJTO, 218, 80, 50, 22, WS_BORDER|ES_NUMBER);
        MK("STATIC", "Entries/page:", IDC_LBL_LJSTEP, 290, 82, 80, 18, SS_LEFT);
        MK("EDIT", "20", IDC_EDT_LJSTEP, 372, 80, 50, 22, WS_BORDER|ES_NUMBER);
        MKHINT("Page k = BASE/?skip=(k-1) x entries. Leave entries at 20 for LiveJournal.",
               18, 104, 552);

        /* ── Row: output / mirror folder ─────────────────────────────────── */
        MK("STATIC", "Mirror folder:", IDC_LBL_MIRROR, 8, 134, 80, 18, SS_LEFT);
        MK("EDIT", "", IDC_EDT_MIRROR, 92, 132, 412, 22, WS_BORDER|ES_AUTOHSCROLL);
        MK("BUTTON", "Browse...", IDC_BTN_MIRROR, 512, 132, 72, 22, BS_PUSHBUTTON);

        /* ── Group: Options ──────────────────────────────────────────────── */
        MK("BUTTON", "Options", IDC_GRP_OPT, 8, 162, 576, 152, BS_GROUPBOX);

        MK("BUTTON", "Export to PDF",  IDC_CHK_EXPORT,   18, 180, 140, 20, BS_AUTOCHECKBOX);
        MK("BUTTON", "Merge into book",IDC_CHK_MERGE,    178,180, 140, 20, BS_AUTOCHECKBOX);
        MK("BUTTON", "Keep comments",  IDC_CHK_COMMENTS, 18, 202, 140, 20, BS_AUTOCHECKBOX);
        MK("BUTTON", "No images",      IDC_CHK_NOIMAGES, 178,202, 140, 20, BS_AUTOCHECKBOX);

        MK("STATIC", "HTML cleaning:", IDC_LBL_CLEAN, 18, 232, 90, 18, SS_LEFT);
        MK("COMBOBOX", "", IDC_CMB_CLEAN, 115, 230, 170, 90,
           CBS_DROPDOWNLIST|WS_VSCROLL);
        SendDlgItemMessageA(hwnd, IDC_CMB_CLEAN, CB_ADDSTRING, 0, (LPARAM)"Off");
        SendDlgItemMessageA(hwnd, IDC_CMB_CLEAN, CB_ADDSTRING, 0, (LPARAM)"Generic (remove ads)");
        SendDlgItemMessageA(hwnd, IDC_CMB_CLEAN, CB_ADDSTRING, 0, (LPARAM)"LiveJournal");
        SendDlgItemMessageA(hwnd, IDC_CMB_CLEAN, CB_SETCURSEL, 2, 0);

        MK("STATIC", "Page size:", IDC_LBL_PAGE, 305, 232, 65, 18, SS_LEFT);
        MK("COMBOBOX", "", IDC_CMB_PAGE, 375, 230, 90, 90,
           CBS_DROPDOWNLIST|WS_VSCROLL);
        SendDlgItemMessageA(hwnd, IDC_CMB_PAGE, CB_ADDSTRING, 0, (LPARAM)"A4");
        SendDlgItemMessageA(hwnd, IDC_CMB_PAGE, CB_ADDSTRING, 0, (LPARAM)"Letter");
        SendDlgItemMessageA(hwnd, IDC_CMB_PAGE, CB_ADDSTRING, 0, (LPARAM)"Legal");
        SendDlgItemMessageA(hwnd, IDC_CMB_PAGE, CB_ADDSTRING, 0, (LPARAM)"A3");
        SendDlgItemMessageA(hwnd, IDC_CMB_PAGE, CB_SETCURSEL, 0, 0);

        MK("STATIC", "Parallel tasks:", IDC_LBL_CONC, 18, 264, 90, 18, SS_LEFT);
        MK("EDIT", "4", IDC_EDT_CONC, 115, 262, 40, 22, WS_BORDER|ES_NUMBER);
        MK("STATIC", "Timeout (sec):", IDC_LBL_TIMEOUT, 175, 264, 90, 18, SS_LEFT);
        MK("EDIT", "30", IDC_EDT_TIMEOUT, 270, 262, 45, 22, WS_BORDER|ES_NUMBER);

        CheckDlgButton(hwnd, IDC_CHK_EXPORT,   BST_CHECKED);
        CheckDlgButton(hwnd, IDC_CHK_MERGE,    BST_CHECKED);
        CheckDlgButton(hwnd, IDC_CHK_COMMENTS, BST_CHECKED);
        CheckDlgButton(hwnd, IDC_CHK_NOIMAGES, BST_UNCHECKED);

        /* ── Group: Advanced ─────────────────────────────────────────────── */
        MK("BUTTON", "Advanced paths", IDC_GRP_ADV, 8, 322, 576, 106, BS_GROUPBOX);

        MK("STATIC", "Chrome / Edge:", IDC_LBL_CHROME, 18, 340, 92, 18, SS_LEFT);
        MK("EDIT", "", IDC_EDT_CHROME, 115, 338, 350, 22, WS_BORDER|ES_AUTOHSCROLL);
        MK("BUTTON", "Browse...", IDC_BTN_CHROME, 473, 338, 72, 22, BS_PUSHBUTTON);
        MKHINT("empty = auto-detect or auto-download (~150 MB on first run)", 115, 362, 430);

        MK("STATIC", "Ghostscript:", IDC_LBL_GS, 18, 382, 92, 18, SS_LEFT);
        MK("EDIT", "", IDC_EDT_GS, 115, 380, 350, 22, WS_BORDER|ES_AUTOHSCROLL);
        MK("BUTTON", "Browse...", IDC_BTN_GS, 473, 380, 72, 22, BS_PUSHBUTTON);
        MKHINT("empty = search PATH; required to merge the book", 115, 404, 430);

        /* ── Start / Cancel / status ─────────────────────────────────────── */
        MK("BUTTON", "Start", IDC_BTN_START, 8, 438, 150, 30, BS_DEFPUSHBUTTON);
        MK("BUTTON", "Cancel", IDC_BTN_CANCEL, 168, 438, 80, 30, BS_PUSHBUTTON);
        MK("STATIC", "Ready.", IDC_LBL_STATUS, 260, 446, 324, 18, SS_LEFT);
        EnableWindow(GetDlgItem(hwnd, IDC_BTN_CANCEL), FALSE);

        /* ── Log ─────────────────────────────────────────────────────────── */
        MKHINT("Log output:", 8, LOG_TOP - 20, 120);
        MK("EDIT", "", IDC_EDT_LOG, 8, LOG_TOP, 576, 240,
           WS_BORDER|ES_MULTILINE|ES_READONLY|ES_AUTOVSCROLL|WS_VSCROLL);

        apply_mode(hwnd);   /* start in folder mode (LJ checkbox unchecked) */
        return 0;
    }

    case WM_SIZE: {
        int cw = LOWORD(lp), ch = HIWORD(lp);
        HWND hlog = GetDlgItem(hwnd, IDC_EDT_LOG);
        if (hlog) {
            int log_h = ch - LOG_TOP - 8;
            if (log_h < 40) log_h = 40;
            SetWindowPos(hlog, NULL, 8, LOG_TOP, cw - 16, log_h,
                         SWP_NOZORDER | SWP_NOACTIVATE);
        }
        return 0;
    }

    case WM_COMMAND: {
        int id = LOWORD(wp);

        if (id == IDC_CHK_LJ) { apply_mode(hwnd); return 0; }

        if (id == IDC_BTN_MIRROR) {
            char path[MAX_PATH] = "";
            int lj = (IsDlgButtonChecked(hwnd, IDC_CHK_LJ) == BST_CHECKED);
            if (browse_folder(hwnd, lj ? "Select the output folder for the book"
                                       : "Select the HTTrack mirror root folder",
                              path, MAX_PATH))
                SetDlgItemTextA(hwnd, IDC_EDT_MIRROR, path);
            return 0;
        }
        if (id == IDC_BTN_CHROME) {
            char path[MAX_PATH] = "";
            if (browse_exe(hwnd, "Select Chrome / Edge / chrome-headless-shell",
                           path, MAX_PATH))
                SetDlgItemTextA(hwnd, IDC_EDT_CHROME, path);
            return 0;
        }
        if (id == IDC_BTN_GS) {
            char path[MAX_PATH] = "";
            if (browse_exe(hwnd, "Select Ghostscript (gswin64c.exe / gswin32c.exe)",
                           path, MAX_PATH))
                SetDlgItemTextA(hwnd, IDC_EDT_GS, path);
            return 0;
        }

        if (id == IDC_BTN_START && !g_running) {
            WorkArgs *a = (WorkArgs *)calloc(1, sizeof(WorkArgs));
            if (!a) {
                MessageBoxA(hwnd, "Out of memory.", "Error", MB_OK|MB_ICONERROR);
                return 0;
            }
            read_cfg(hwnd, &a->cfg);
            a->lj_mode = (IsDlgButtonChecked(hwnd, IDC_CHK_LJ) == BST_CHECKED);
            GetDlgItemTextA(hwnd, IDC_EDT_MIRROR, a->mirror, MAX_PATH);

            if (a->lj_mode) {
                char buf[32];
                GetDlgItemTextA(hwnd, IDC_EDT_LJURL, a->lj_url, sizeof(a->lj_url));
                GetDlgItemTextA(hwnd, IDC_EDT_LJFROM, buf, sizeof(buf));
                a->lj_from = atoi(buf);
                GetDlgItemTextA(hwnd, IDC_EDT_LJTO, buf, sizeof(buf));
                a->lj_to = atoi(buf);
                GetDlgItemTextA(hwnd, IDC_EDT_LJSTEP, buf, sizeof(buf));
                a->lj_step = atoi(buf);
                if (a->lj_from < 1) a->lj_from = 1;
                if (a->lj_to < a->lj_from) a->lj_to = a->lj_from;
                if (a->lj_step < 1) a->lj_step = 20;
                /* require a real URL */
                if (strncmp(a->lj_url, "http://", 7) != 0 &&
                    strncmp(a->lj_url, "https://", 8) != 0) {
                    free(a);
                    MessageBoxA(hwnd,
                        "Please enter the blog URL, e.g.\n"
                        "https://someblog.livejournal.com",
                        "Blog URL required", MB_OK|MB_ICONWARNING);
                    return 0;
                }
                a->cfg.do_merge = 1;   /* a book is always merged */
            } else {
                if (!a->mirror[0]) {
                    free(a);
                    MessageBoxA(hwnd,
                        "Please select a mirror folder first.\n\n"
                        "This is the folder where HTTrack saved the website.",
                        "No folder selected", MB_OK|MB_ICONWARNING);
                    return 0;
                }
            }

            SetDlgItemTextA(hwnd, IDC_EDT_LOG, "");
            htspdf_cancel = 0;
            htspdf_log_cb = gui_log;
            htspdf_log_ud = NULL;
            g_running = 1;
            EnableWindow(GetDlgItem(hwnd, IDC_BTN_START),  FALSE);
            EnableWindow(GetDlgItem(hwnd, IDC_BTN_CANCEL), TRUE);
            set_status(hwnd, a->lj_mode ? "Downloading & converting..."
                                        : "Converting...");
            DWORD tid;
            g_thread = CreateThread(NULL, 0, worker_proc, a, 0, &tid);
            if (!g_thread) {
                htspdf_log_cb = NULL;
                g_running = 0;
                free(a);
                EnableWindow(GetDlgItem(hwnd, IDC_BTN_START),  TRUE);
                EnableWindow(GetDlgItem(hwnd, IDC_BTN_CANCEL), FALSE);
                set_status(hwnd, "Error: could not start thread.");
                MessageBoxA(hwnd, "Failed to start worker thread.", "Error",
                            MB_OK|MB_ICONERROR);
            }
            return 0;
        }

        if (id == IDC_BTN_CANCEL && g_running) {
            htspdf_cancel = 1;
            EnableWindow(GetDlgItem(hwnd, IDC_BTN_CANCEL), FALSE);
            set_status(hwnd, "Cancelling...");
            log_append(GetDlgItem(hwnd, IDC_EDT_LOG),
                       "[GUI] Cancel requested - waiting for current tasks...");
            return 0;
        }
        return 0;
    }

    case WM_LOG_LINE: {
        char *line = (char *)lp;
        if (line) {
            log_append(GetDlgItem(hwnd, IDC_EDT_LOG), line);
            free(line);
        }
        return 0;
    }

    case WM_WORK_DONE: {
        int ok = (int)(UINT_PTR)wp;
        htspdf_log_cb = NULL;
        htspdf_cancel = 0;
        g_running = 0;
        if (g_thread) { CloseHandle(g_thread); g_thread = NULL; }
        EnableWindow(GetDlgItem(hwnd, IDC_BTN_START),  TRUE);
        EnableWindow(GetDlgItem(hwnd, IDC_BTN_CANCEL), FALSE);
        char status[128];
        snprintf(status, sizeof(status), "Done - %d page(s) converted.", ok);
        set_status(hwnd, status);
        char logmsg[160];
        snprintf(logmsg, sizeof(logmsg),
                 "\r\n[Done] %d page(s) converted. If 'Merge into book' was on, "
                 "see book.pdf in the output folder.", ok);
        log_append(GetDlgItem(hwnd, IDC_EDT_LOG), logmsg);
        return 0;
    }

    case WM_CLOSE:
        if (g_running) {
            if (MessageBoxA(hwnd,
                    "Conversion is still running.\nQuit anyway? "
                    "(the book will be incomplete)",
                    "httrack2pdf", MB_YESNO|MB_ICONQUESTION) != IDYES)
                return 0;
            htspdf_cancel = 1;
            g_hwnd = NULL;          /* make pending PostMessage()s no-ops */
        }
        DestroyWindow(hwnd);
        return 0;

    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }

    return DefWindowProcA(hwnd, msg, wp, lp);
}

#undef MK
#undef MKHINT

/* ── WinMain ─────────────────────────────────────────────────────────────── */
int WINAPI WinMain(HINSTANCE hInst, HINSTANCE hPrev,
                   LPSTR lpCmdLine, int nCmdShow)
{
    (void)hPrev;
    (void)lpCmdLine;

    INITCOMMONCONTROLSEX icc = { sizeof(icc), ICC_STANDARD_CLASSES };
    InitCommonControlsEx(&icc);
    CoInitialize(NULL);

    WNDCLASSA wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.style         = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc   = WndProc;
    wc.hInstance     = hInst;
    wc.hIcon         = LoadIconA(NULL, IDI_APPLICATION);
    wc.hCursor       = LoadCursorA(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.lpszClassName = "httrack2pdf_wnd";
    if (!RegisterClassA(&wc)) {
        MessageBoxA(NULL, "RegisterClass failed.", "Fatal", MB_OK|MB_ICONERROR);
        return 1;
    }

    g_hwnd = CreateWindowExA(
        0, "httrack2pdf_wnd",
        "httrack2pdf - blog / mirror -> PDF book",
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT,
        604, 800,
        NULL, NULL, hInst, NULL);
    if (!g_hwnd) {
        MessageBoxA(NULL, "CreateWindow failed.", "Fatal", MB_OK|MB_ICONERROR);
        return 1;
    }

    ShowWindow(g_hwnd, nCmdShow);
    UpdateWindow(g_hwnd);

    MSG msg;
    while (GetMessageA(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageA(&msg);
    }

    CoUninitialize();
    return (int)msg.wParam;
}
