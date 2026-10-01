#!/usr/bin/env bash
# Local dev smoke test: real backend process, real Deepgram, real audio fixture.
#
# Credentials are read from the environment ONLY. Nothing is written to source
# control and nothing reaches the APK -- the app talks to this proxy, never to
# a provider.
#
#   DEEPGRAM_API_KEY=... ./smoke_local.sh <audio-file>
#
# Exits non-zero if the round trip fails. Costs one Deepgram call.
set -u
PORT=${PORT:-8099}
# curl here is a Windows binary: it cannot open an MSYS /c/... path, and a
# space in the path breaks -F parsing. Stage the fixture somewhere plain and
# hand curl a native path.
AUDIO_SRC=${1:-}
[ -n "$AUDIO_SRC" ] || { echo "usage: DEEPGRAM_API_KEY=... $0 <audio-file>"; exit 2; }
FIXDIR=$(pwd)/_fixture
mkdir -p "$FIXDIR"
cp "$AUDIO_SRC" "$FIXDIR/clip.wav" 2>/dev/null
AUDIO="$(pwd -W 2>/dev/null || pwd)/_fixture/clip.wav"

if [ -z "${DEEPGRAM_API_KEY:-}" ]; then
  echo "BLOCKED: DEEPGRAM_API_KEY not in environment. Not reading it from disk."
  exit 2
fi
[ -f "$FIXDIR/clip.wav" ] || { echo "BLOCKED: no audio fixture at $AUDIO_SRC"; exit 2; }

python -m uvicorn app:app --port "$PORT" --log-level warning &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$FIXDIR"' EXIT
for i in $(seq 1 30); do
  curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null && break
  sleep 1
done

echo "--- healthz ---"
curl -s "http://127.0.0.1:$PORT/healthz"; echo

echo "--- register an installation (client auth) ---"
TOKEN=$(curl -s -X POST "http://127.0.0.1:$PORT/v1/installations" \
  | python -c "import sys,json; print(json.load(sys.stdin)['token'])")
[ -n "$TOKEN" ] || { echo "FAIL: registration"; exit 1; }
echo "  registered (token not printed)"

echo "--- no token must be refused before any provider call ---"
U=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/v1/transcribe" \
  -F "provider=deepgram" -F "file=@$AUDIO;type=audio/wav")
[ "$U" = "401" ] && echo "  PASS 401" || { echo "  FAIL got $U"; exit 1; }

KEY="smoke-$(date +%s)"
echo "--- transcribe (idempotency key $KEY) ---"
BODY=$(curl -s -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT/v1/transcribe" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" \
  -F "provider=deepgram" -F "language=hi" -F "keyterm=Venkatesh" \
  -F "file=@$AUDIO;type=audio/wav")
STATUS=$(echo "$BODY" | tail -1)
echo "status: $STATUS"
# Length only: transcript text is not echoed, even to an operator terminal.
echo "$BODY" | head -n -1 | python -c "import sys,json; d=json.load(sys.stdin); print('  chars:', len(d.get('text') or ''), '| provider:', d.get('provider'))" 2>/dev/null || echo "  (non-JSON body)"

echo "--- replay: same key must NOT re-bill ---"
R=$(curl -s -X POST "http://127.0.0.1:$PORT/v1/transcribe" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" \
  -F "provider=deepgram" -F "file=@$AUDIO;type=audio/wav")
echo "$R" | grep -q '"replayed": *true' \
  && echo "  PASS replayed from cache" || echo "  FAIL not replayed"

[ "$STATUS" = "200" ] || exit 1
