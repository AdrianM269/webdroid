#!/data/data/com.termux/files/usr/bin/bash
# Serves the Termux workspace over http://localhost:8899 so WebDroid can
# preview files that live in Termux home.
#
# Why this exists: Android 11+ hides other apps' private data entirely, so
# WebDroid can never read /data/data/com.termux/... -- File.exists() is false
# and no permission flag changes that. Loopback is shared by every app, so
# serving over HTTP is the way through.
#
# Usage:  ./serve.sh [port]     (default 8899)
# Then:   curl http://127.0.0.1:8765/navigate -d '{"url":"http://localhost:8899/page.html"}'
set -u

WORKSPACE="${WEBDROID_WORKSPACE:-$HOME/workspace}"
PORT="${1:-${WEBDROID_PORT:-8899}}"

cd "$WORKSPACE" || {
  echo "serve.sh: no workspace directory at $WORKSPACE" >&2
  echo "          set WEBDROID_WORKSPACE=/path/to/dir and retry" >&2
  exit 1
}

# 127.0.0.1 only: this must never be reachable from the local network.
exec python3 -m http.server "$PORT" --bind 127.0.0.1
