# Deploying the backend

The backend is designed to run as **one process on one host**, behind a
TLS-terminating reverse proxy. This guide uses a small Linux VM with systemd
and Caddy; any equivalent setup works if it meets the requirements below.

## Requirements

| Requirement | Why |
|---|---|
| **TLS only** | The service receives raw user audio. Terminate HTTPS in front of it, redirect or close plain HTTP, and enable HSTS. The Android app refuses non-HTTPS release URLs |
| **Keys in the environment only** | Provider keys and secrets live in a root-only environment file, never in the repository, an image layer or a command line. Keys can be rotated without rebuilding the app |
| **Exactly one worker and one instance** | Idempotency, in-flight jobs and per-minute counters are held in process memory. A second worker or replica could bill a retry twice and split the limits. `/healthz` reports `"idempotency_shared": false` as a reminder |
| **Persistent storage for `METERING_DB`** | The SQLite database holds usage, installation token hashes and provider health. It contains no transcript text and no plaintext tokens |
| **Proxy body limit ≥ 100 MB** | Uploads can be up to 100 MB (`shared/limits.json`); a smaller proxy limit rejects them before the app can answer |
| **Proxy timeouts ≥ 330 s** | A single request may wait up to the router deadline (280 s) for provider work |
| **No transcript content in logs** | Log lines carry provider, byte count, duration, status and transcript *length* only |

## 1. Install

Package `backend/` and `shared/` (the backend reads `../shared/limits.json`),
excluding local state:

```bash
tar czf irela-backend.tgz --exclude='metering.sqlite3' --exclude='.env' \
    --exclude='__pycache__' --exclude='.pytest_cache' --exclude='_fixture' backend shared
scp irela-backend.tgz <user>@<server>:/tmp/
```

On the server:

```bash
sudo apt update && sudo apt install -y python3-venv sqlite3
sudo useradd --system --home /opt/apex --shell /usr/sbin/nologin apex
sudo mkdir -p /opt/apex /var/lib/apex /etc/apex
sudo tar xzf /tmp/irela-backend.tgz -C /opt/apex
sudo python3 -m venv /opt/apex/venv
sudo /opt/apex/venv/bin/pip install -r /opt/apex/backend/requirements.txt
sudo chown -R apex:apex /opt/apex /var/lib/apex && sudo chmod 700 /var/lib/apex
cd /opt/apex/backend && sudo -u apex /opt/apex/venv/bin/python -m pytest -q -p no:cacheprovider
```

The test suite never contacts a provider.

## 2. Configure

Create a root-only environment file, then add the keys without echoing them to
the terminal or shell history:

```bash
sudo install -m 600 -o root -g root /dev/null /etc/apex/backend.env
read -rs KEY && printf 'DEEPGRAM_API_KEY=%s\n' "$KEY" | sudo tee -a /etc/apex/backend.env >/dev/null; unset KEY
read -rs KEY && printf 'ASSEMBLYAI_API_KEY=%s\n' "$KEY" | sudo tee -a /etc/apex/backend.env >/dev/null; unset KEY
sudo tee -a /etc/apex/backend.env >/dev/null <<'ENV'
METERING_DB=/var/lib/apex/metering.sqlite3
TRANSCRIPTION_ENABLED=true
DAILY_BUDGET_INR=300
USAGE_ALERT_INR=150
FREE_DAILY_NETWORK_SECONDS=3600
FREE_DAILY_BUDGET_INR=100
ENV
```

The backup-provider key is optional. Every variable, with its default, is listed
in [`.env.example`](.env.example). Leave `MAX_UPLOAD_BYTES` and
`MAX_AUDIO_SECONDS` unset; the limits come from `shared/limits.json`, and
overrides can only tighten them. Optional features (owner plan, operator token,
Google Play billing) stay off until their variables are set.

Environment changes take effect only after `sudo systemctl restart apex-backend`.

## 3. Run as a service

`/etc/systemd/system/apex-backend.service`:

```ini
[Unit]
Description=Irela transcription backend (single instance)
After=network-online.target
Wants=network-online.target

[Service]
User=apex
Group=apex
WorkingDirectory=/opt/apex/backend
EnvironmentFile=/etc/apex/backend.env
ExecStart=/opt/apex/venv/bin/uvicorn app:app --host 127.0.0.1 --port 8080 --workers 1 --proxy-headers --forwarded-allow-ips 127.0.0.1
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/apex
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now apex-backend
```

- `--host 127.0.0.1`: reachable only through the local reverse proxy.
- `--workers 1`: required (see Requirements).
- `--proxy-headers --forwarded-allow-ips 127.0.0.1`: trust the client address
  from the local proxy, which the per-address registration limit depends on.

## 4. TLS reverse proxy (Caddy)

Install Caddy from its official repository, then configure
`/etc/caddy/Caddyfile`:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

```
api.example.com {
    request_body {
        max_size 105MB
    }
    reverse_proxy 127.0.0.1:8080 {
        transport http {
            read_timeout 330s
            write_timeout 330s
        }
    }
    header Strict-Transport-Security "max-age=31536000"
}
```

```bash
sudo systemctl reload caddy
```

Only ports 80 and 443 should be open to the internet; port 8080 stays closed.

## 5. Verify

```bash
curl -fsS https://api.example.com/healthz
```

Before serving traffic, check:
- `"ok": true` and `"transcription_enabled": true`;
- `providers` shows `true` for each configured key, and `provider_health` is `healthy`;
- `limits` reads 3600 seconds and 104857600 bytes;
- `"idempotency_shared": false`, so the service must stay at one instance.

A request without a token must be refused:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.example.com/v1/transcribe   # expect 401
```

`backend/preflight_local.sh` runs the same refusal checks against a throwaway
local server with no provider keys.

## Operations

| Task | How |
|---|---|
| Stop all provider spending | Set `TRANSCRIPTION_ENABLED=false` and restart; every transcription is refused with `503`, `retryable: false` |
| Automatic spending stop | `DAILY_BUDGET_INR` refuses provider calls once the 24-hour estimate reaches it |
| Spend and usage | `GET /v1/usage?since_hours=24` (requires `Authorization: Bearer <ADMIN_TOKEN>` when that variable is set) |
| Spend alert | `journalctl -u apex-backend -f \| grep USAGE_ALERT` |
| Back up the database | `sqlite3 /var/lib/apex/metering.sqlite3 ".backup '/var/backups/apex-$(date +%F).sqlite3'"` |
| Rotate a provider key | Edit `/etc/apex/backend.env` and restart; the app needs no update |
| Log retention | `sudo journalctl --vacuum-time=30d` |

Losing the database resets quotas and invalidates installation tokens; the app
re-registers automatically, but usage history is lost.

## Scaling beyond one instance

Running more than one instance first needs a shared store for idempotency and
metering, with durable atomic put-if-absent. `IdempotencyStore` is already an
interface, so this is a new implementation rather than a redesign.
