#!/usr/bin/env bash
# Fetches the whisper.cpp sources that android/lib compiles into the JNI library.
#
#   ./scripts/fetch-whisper-cpp.sh            # run from the repository root
#
# Pinned to the upstream release the app was developed and tested against.
# The sources go to third_party/whisper.cpp, which is git-ignored.
set -euo pipefail

WHISPER_CPP_REPO="https://github.com/ggml-org/whisper.cpp.git"
WHISPER_CPP_TAG="${WHISPER_CPP_TAG:-v1.9.3}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/whisper.cpp"

if [ -f "$DEST/src/whisper.cpp" ]; then
  echo "whisper.cpp already present at $DEST (delete it to re-fetch)."
  exit 0
fi

mkdir -p "$ROOT/third_party"
git clone --depth 1 --branch "$WHISPER_CPP_TAG" "$WHISPER_CPP_REPO" "$DEST"
echo "Fetched whisper.cpp $WHISPER_CPP_TAG into $DEST"
