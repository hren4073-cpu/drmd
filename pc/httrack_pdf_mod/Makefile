# Makefile for the httrack_pdf module (standalone test + wrapper plugin).
#
# Targets:
#   make exe          -> httrack2pdf(.exe)    (self-contained CLI release binary;
#                                              autorun: downloads headless
#                                              Chromium on first run)
#   make gui          -> httrack2pdf.exe      (Windows GUI version with Win32
#                                              window; MinGW only)
#   make standalone   -> httrack_pdf_test     (dev build of the same code)
#   make plugin       -> httrack_pdf.so/.dll  (HTTrack --wrapper plugin)
#   make clean
#
# For the *integrated* build (native --pdf-* options) see README.md:
# copy httrack_pdf.[ch] into httrack/src, apply patches/, rebuild HTTrack.
#
# Override HTSRC to point at the HTTrack source tree (for the plugin build):
#   make plugin HTSRC=/path/to/httrack-master

HTSRC   ?= ../httrack-master
CC      ?= gcc
CFLAGS  ?= -O2 -std=gnu99 -Wall -D_REENTRANT
INCS     = -I$(HTSRC) -I$(HTSRC)/src

UNAME_S := $(shell uname -s 2>/dev/null)
ifeq ($(OS),Windows_NT)
  SOEXT = dll
  PLUGIN_LDFLAGS = -shared
  STANDALONE_EXE = httrack_pdf_test.exe
  RELEASE_BIN = httrack2pdf.exe
  # MinGW: fully static so the .exe runs on any Windows 10/11 with no DLLs.
  RELEASE_FLAGS = -static -static-libgcc -s
else
  SOEXT = so
  PLUGIN_LDFLAGS = -shared -fPIC
  STANDALONE_EXE = httrack_pdf_test
  RELEASE_BIN = httrack2pdf
  RELEASE_FLAGS = -s
endif

.PHONY: all exe gui standalone plugin clean

all: exe

# Self-contained CLI release binary (autorun browser bootstrap on first run).
exe: httrack_pdf.c httrack_pdf.h
	$(CC) $(CFLAGS) -DHTSPDF_STANDALONE $(RELEASE_FLAGS) -o $(RELEASE_BIN) httrack_pdf.c
	@echo "[ok] built $(RELEASE_BIN)"

# Win32 GUI binary (Windows / MinGW only).
gui: httrack_pdf_gui.c httrack_pdf.c httrack_pdf.h
ifeq ($(OS),Windows_NT)
	$(CC) $(CFLAGS) -DHTSPDF_STANDALONE -DHTSPDF_GUI \
	    -static -static-libgcc -s -mwindows \
	    -o httrack2pdf.exe httrack_pdf_gui.c httrack_pdf.c \
	    -lcomdlg32 -lshell32 -lole32 -lcomctl32
	@echo "[ok] built httrack2pdf.exe (GUI)"
else
	@echo "[skip] 'make gui' is Windows-only (MinGW). Use 'make exe' on Linux."
endif

# Dev build of the same standalone code (no extra flags).
standalone: httrack_pdf.c httrack_pdf.h
	$(CC) $(CFLAGS) -DHTSPDF_STANDALONE -o $(STANDALONE_EXE) httrack_pdf.c

# HTTrack external wrapper plugin (loaded via: httrack ... --wrapper httrack_pdf,...).
plugin: httrack_pdf.c httrack_pdf.h
	$(CC) $(CFLAGS) -fPIC $(PLUGIN_LDFLAGS) $(INCS) \
	    -o httrack_pdf.$(SOEXT) httrack_pdf.c

clean:
	rm -f httrack_pdf_test httrack_pdf_test.exe httrack2pdf httrack2pdf.exe \
	      httrack_pdf.so httrack_pdf.dll *.o
