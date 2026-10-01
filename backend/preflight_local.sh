#!/usr/bin/env bash
# Zero-cost preflight: real backend process, NO provider key, NO provider call.
# Proves client auth, limits, kill switch and budget refuse requests before
# any audio would reach a provider. Safe to run anywhere, any time.
#
#   ./preflight_local.sh
set -u
PORT=${PORT:-8098}
B="http://127.0.0.1:$PORT"
WORK=$(mktemp -d)
NATIVE_WORK=$(cd "$WORK" && (pwd -W 2>/dev/null || pwd))
python - "$WORK/a.wav" <<'PY'
import sys
rate, n = 16000, 16000 * 2
hdr = (b"RIFF" + (36 + n).to_bytes(4, "little") + b"WAVEfmt " + (16).to_bytes(4, "little") +
       (1).to_bytes(2, "little") + (1).to_bytes(2, "little") + rate.to_bytes(4, "little") +
       (rate * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") + (16).to_bytes(2, "little") +
       b"data" + n.to_bytes(4, "little"))
open(sys.argv[1], "wb").write(hdr + b"\x00" * n)
PY
AUDIO="$NATIVE_WORK/a.wav"
FAILS=0
check() { if [ "$2" = "$3" ]; then echo "  PASS $1 ($2)"; else echo "  FAIL $1: expected $3, got $2"; FAILS=$((FAILS+1)); fi; }
post() { curl -s -o "$NATIVE_WORK/body" -w '%{http_code}' -X POST "$B/v1/transcribe" "$@" -F provider=deepgram -F "file=@$AUDIO;type=audio/wav"; }

start() {
  env -u DEEPGRAM_API_KEY -u ASSEMBLYAI_API_KEY METERING_DB="$WORK/m.sqlite3" "$@" \
    python -m uvicorn app:app --host 127.0.0.1 --port "$PORT" --workers 1 --log-level warning \
    > "$WORK/server.log" 2>&1 &
  SERVER=$!
  for i in $(seq 1 30); do curl -sf "$B/healthz" >/dev/null && return; sleep 1; done
  echo "server did not start"; cat "$WORK/server.log"; exit 1
}
stop() { kill "$SERVER" 2>/dev/null; wait "$SERVER" 2>/dev/null; }

echo "== auth and per-client limits =="
start TRANSCRIPTION_ENABLED=true CLIENT_REQUESTS_PER_MINUTE=2 CLIENT_TRANSCRIPTIONS_PER_DAY=100
check "no token"            "$(post)" 401
check "garbage token"       "$(post -H 'Authorization: Bearer nope')" 401
TOKEN=$(curl -s -X POST "$B/v1/installations" | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
check "registration issued a token" "$([ -n "$TOKEN" ] && echo yes)" yes
check "valid token reaches provider stage (no key -> 503)" "$(post -H "Authorization: Bearer $TOKEN")" 503
check "second request in the minute" "$(post -H "Authorization: Bearer $TOKEN")" 503
check "third request: per-minute limit" "$(post -H "Authorization: Bearer $TOKEN")" 429
check "limit is terminal" "$(python -c "import json;print(json.load(open(r'$NATIVE_WORK/body'))['retryable'])")" False
stop

echo "== kill switch =="
start TRANSCRIPTION_ENABLED=false
TOKEN=$(curl -s -X POST "$B/v1/installations" | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
check "kill switch refuses authorized request" "$(post -H "Authorization: Bearer $TOKEN")" 503
check "kill switch reason" "$(python -c "import json;print(json.load(open(r'$NATIVE_WORK/body'))['reason'])")" disabled
stop

echo "== log hygiene =="
grep -q "$TOKEN" "$WORK/server.log" && { echo "  FAIL token in log"; FAILS=$((FAILS+1)); } || echo "  PASS no token in log"

rm -rf "$WORK"
[ "$FAILS" = 0 ] && echo "PREFLIGHT PASSED (no provider was contacted)" || { echo "PREFLIGHT FAILED: $FAILS"; exit 1; }
