#!/bin/sh
# ----------------------------------------------------------------------
# Build ./httrack2pdf - the self-contained Linux release binary.
# "Autorun": on first run, if no Chrome/Chromium is found, it downloads a
# portable headless Chromium next to itself (needs curl, python3, unzip).
# ----------------------------------------------------------------------
set -e
CC="${CC:-gcc}"
"$CC" -O2 -std=gnu99 -Wall -DHTSPDF_STANDALONE -s -o httrack2pdf httrack_pdf.c
echo "[ok] built ./httrack2pdf"
echo "Example: ./httrack2pdf /path/to/mirror 'export,merge,clean=lj,pagesize=A4,concurrency=4'"
