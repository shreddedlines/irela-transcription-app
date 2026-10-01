# Conversation transcription: what is captured, and what is not

Verified on a physical Android 16 device. The "Status" column below records
what was actually observed there.

## What the feature does

"Transcribe conversation" records **what this phone's microphone hears** and
transcribes it, through the same pipeline as any recording: streaming WAV,
60-minute limit, AAC/M4A cloud upload, persistent job, retry, provider failover.

It does **not** record phone calls or WhatsApp/VoIP calls. Android does not let
ordinary apps capture call audio, and this app does not try to work around that.

Android's own recording indicator and the app's "Recording" notification are
shown for the whole recording. Nothing is hidden from the user or from anyone
nearby.

## Capture sources

| Source | How | Status |
|---|---|---|
| Microphone, app on screen | `AudioRecord(MIC)`, RECORD_AUDIO | **SUPPORTED** — device-verified |
| Microphone after switching apps | Foreground service type `microphone`, started from the visible app | **SUPPORTED** — device-verified: 12 s captured with the launcher in front, RMS -30.5 dBFS, not silenced, service `types=0x80` |
| Audio played by other apps | AudioPlaybackCapture + MediaProjection, per-session system consent, foreground service type `mediaProjection` | **PARTIALLY_SUPPORTED** — consent dialog and denial path device-verified; the granted path needs a person to accept Android's dialog and was not exercised. Only media/game/unknown audio from apps that allow capture; never call audio |
| Both sides of a cellular call | Would need `VOICE_CALL`/`VOICE_DOWNLINK`, which require `CAPTURE_AUDIO_OUTPUT` (privileged) | **NOT_SUPPORTED_FOR_ORDINARY_THIRD_PARTY_APPS** — not attempted |
| Both sides of a WhatsApp/VoIP call | Call audio is `USAGE_VOICE_COMMUNICATION`, excluded from playback capture | **NOT_SUPPORTED_FOR_ORDINARY_THIRD_PARTY_APPS** — not attempted |
| Microphone during a call on loudspeaker | The microphone may hear the speaker, but Android may silence other apps while a call holds the audio mode | **DEVICE_DEPENDENT** — not demonstrated (needs a person to place a call). Treat as unsupported until verified |
| Wired headset microphone | Follows system routing | **DEVICE_DEPENDENT** — not tested |
| Bluetooth headset microphone | Needs the SCO voice link, which the app does not start | **DEVICE_DEPENDENT** — not tested |
| Android 15/16 rules | FGS types + FOREGROUND_SERVICE_MICROPHONE / _MEDIA_PROJECTION permissions, while-in-use rule, `startForeground(mediaProjection)` before `getMediaProjection` | **SUPPORTED** — built for targetSdk 36, run on Android 16 |
| OEM behaviour | Background limits, concurrent-capture silencing | **DEVICE_DEPENDENT** — observed only on the test device |

The same table lives in code (`capture/CapturePolicy.kt`, `CaptureFeasibility`)
and is asserted by `ConversationCaptureTest`, so the app's claims cannot drift
from this document.

## What the user is told

| Situation | Message |
|---|---|
| Asks to record both sides of a call | "Android does not let apps record phone or WhatsApp call audio, so this app cannot capture both sides of a call. You can record what the microphone hears instead." |
| Denies Android's playback-capture consent | "Permission to capture audio playing on this phone was not granted. Nothing was recorded." |
| A call is active when recording | "A call appears to be active. Android may give apps silence during calls, and this app cannot record the other side of phone or WhatsApp calls." |
| Android is silencing the capture (`AudioRecordingConfiguration.isClientSilenced`) | "Android is not giving this app any audio right now (usually while a call is active). Nothing useful is being recorded." |
| Microphone unavailable | "The microphone is not available right now (another app or the system may be using it). Nothing was recorded." |
| Android refuses background recording | "Android did not allow recording in the background. Keep this screen open while recording." |

Silencing and call state are detected with public APIs only, to inform the
user, never to circumvent the silencing.

## Recording state machine

```
                    start (policy allows; FGS microphone started)
        IDLE ─────────────────────────────────────────────▶ RECORDING
         ▲                                                   │   ▲
         │                                             pause │   │ resume
         │                                                   ▼   │
         │                                                  PAUSED
         │
         ├── stop ────────────── finalise header, rename .wav.recording → .wav ──▶ job (consent gate)
         ├── 3,600,000 ms ────── same, automatically (screen if attached, else job sink)
         ├── input lost ──────── same if any audio captured; else visible failure, no file
         ├── cancel ──────────── delete .wav.recording, no job
         └── process death ───── .wav.recording on disk, header current
                                   └─ next launch: header repaired, renamed,
                                      FAILED/interrupted job with Try again
```

A `.wav.recording` file is never an upload input; only a finalised `.wav` is.
