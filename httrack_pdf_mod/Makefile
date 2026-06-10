# Makefile for the httrack_pdf module (standalone test + wrapper plugin).
#
# Targets:
#   make standalone   -> httrack_pdf_test     (no HTTrack needed; test a mirror)
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
else
  SOEXT = so
  PLUGIN_LDFLAGS = -shared -fPIC
  STANDALONE_EXE = httrack_pdf_test
endif

.PHONY: all standalone plugin clean

all: standalone

# Self-contained test tool: needs NO HTTrack headers.
standalone: httrack_pdf.c httrack_pdf.h
	$(CC) $(CFLAGS) -DHTSPDF_STANDALONE -o $(STANDALONE_EXE) httrack_pdf.c

# HTTrack external wrapper plugin (loaded via: httrack ... --wrapper httrack_pdf,...).
plugin: httrack_pdf.c httrack_pdf.h
	$(CC) $(CFLAGS) -fPIC $(PLUGIN_LDFLAGS) $(INCS) \
	    -o httrack_pdf.$(SOEXT) httrack_pdf.c

clean:
	rm -f httrack_pdf_test httrack_pdf_test.exe httrack_pdf.so httrack_pdf.dll *.o
