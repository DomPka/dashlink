#!/usr/bin/env bash
# Looks through a logcat dump for a crash or an "application not responding" of DashLink.
# Prints what it finds and exits with status 1 if there was one.
#
#   ci/crashes.sh logcat.txt
LOG="${1:?usage: crashes.sh logcat.txt}"
PKG=com.dompka.dashlink
PKG_RE='com\.dompka\.dashlink'

if [ ! -s "$LOG" ]; then
    echo "crashes.sh: $LOG is missing or empty - cannot tell whether the app crashed" >&2
    exit 1
fi

# A Java crash ("FATAL EXCEPTION" followed by "Process: <package>"), an ANR, a native crash, and what
# Android logs when it has to close a crashed screen.
PATTERN="AndroidRuntime: Process: $PKG_RE|ANR in $PKG_RE|Fatal signal.*dompka\.dashlink|Force finishing activity $PKG_RE"

if grep -E -q "$PATTERN" "$LOG"; then
    echo "CRASH OR ANR OF $PKG FOUND IN $LOG:" >&2
    grep -E -n -B3 -A30 "$PATTERN" "$LOG" | head -300 >&2
    exit 1
fi
echo "No crash or ANR of $PKG in $LOG ($(wc -l < "$LOG") lines)."
