package com.whispercppdemo.capture

/**
 * What this app can and cannot capture, stated honestly, and the decisions that
 * follow from it. Pure Kotlin: every rule is a JVM test.
 *
 * The app records only what Android lets an ordinary app record, through
 * supported APIs, with the system's own indicators and consent dialogs left
 * exactly as they are. There is no hidden, privileged, root or non-SDK path,
 * and none will be added. Protected call audio is reported as unsupported, not
 * approximated through a workaround.
 */

/** Where a recording's audio comes from. */
enum class CaptureMode {
    /** The phone's microphone: the room, including a call on loudspeaker if Android allows it. */
    MICROPHONE,

    /**
     * Audio other apps are playing, via AudioPlaybackCapture (Android 10+),
     * only after the user grants Android's screen-capture consent, and only
     * from apps that allow capture. Never call audio.
     */
    PLAYBACK
}

enum class Support {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    NOT_SUPPORTED_FOR_ORDINARY_THIRD_PARTY_APPS,
    DEVICE_DEPENDENT
}

/** One row of the feasibility audit. [onTestDevice] is only what was actually observed. */
data class CaptureScenario(
    val id: String,
    val title: String,
    val support: Support,
    val detail: String,
    val onTestDevice: String
)

object CaptureFeasibility {
    const val TEST_DEVICE = "Nothing A059, Android 16"

    val scenarios: List<CaptureScenario> = listOf(
        CaptureScenario("mic_visible", "Microphone while the app is on screen", Support.SUPPORTED,
            "RECORD_AUDIO runtime permission. Android shows its microphone indicator.",
            "Verified: real 60-minute recording, short UI recordings, pause/resume/stop/cancel."),
        CaptureScenario("mic_foreground_service", "Microphone after switching to another app", Support.SUPPORTED,
            "Needs a foreground service of type microphone started while the app is visible " +
                    "(Android 11+ while-in-use rule; Android 14+ also FOREGROUND_SERVICE_MICROPHONE). " +
                    "A notification is shown for the whole recording. Cannot be started from the background.",
            "See CaptureDeviceTest: capture continuing with the app in the background."),
        CaptureScenario("playback_capture", "Audio played by other apps", Support.PARTIALLY_SUPPORTED,
            "AudioPlaybackCapture, Android 10+, only after the user accepts Android's screen-capture " +
                    "consent each session, and only for USAGE_MEDIA/GAME/UNKNOWN audio from apps that " +
                    "allow capture. Apps can opt out; voice-call audio is never included.",
            "Consent dialog and denial path exercised; granted capture requires the user to accept the system dialog."),
        CaptureScenario("cellular_call", "Both sides of a cellular phone call", Support.NOT_SUPPORTED_FOR_ORDINARY_THIRD_PARTY_APPS,
            "The call audio sources (VOICE_CALL, VOICE_UPLINK, VOICE_DOWNLINK) require the " +
                    "CAPTURE_AUDIO_OUTPUT privileged permission, which ordinary apps cannot hold.",
            "Not attempted. Not supported."),
        CaptureScenario("voip_call", "Both sides of a WhatsApp / VoIP call", Support.NOT_SUPPORTED_FOR_ORDINARY_THIRD_PARTY_APPS,
            "Call audio is USAGE_VOICE_COMMUNICATION, which AudioPlaybackCapture never captures; the " +
                    "remote party's audio is not available to other apps.",
            "Not attempted. Not supported."),
        CaptureScenario("speakerphone_during_call", "Microphone during a call on loudspeaker", Support.DEVICE_DEPENDENT,
            "The microphone may hear the loudspeaker, but while a call holds the audio mode Android " +
                    "can give other apps silence (concurrent-capture policy, Android 10+). The app detects " +
                    "an active call and system silencing and says so; it never claims the call was recorded.",
            "Not demonstrated: requires a user-placed call. Treat as unsupported until verified."),
        CaptureScenario("wired_headset", "Wired headset microphone", Support.DEVICE_DEPENDENT,
            "MIC source follows the system's routing to a connected headset microphone; outside a call it " +
                    "is the same path as the built-in microphone.",
            "Not tested (no wired headset)."),
        CaptureScenario("bluetooth_headset", "Bluetooth headset microphone", Support.DEVICE_DEPENDENT,
            "Bluetooth microphones use the SCO voice link, which the system usually routes only for " +
                    "communication. Without it the phone's own microphone is used.",
            "Not tested. The app does not start Bluetooth SCO."),
        CaptureScenario("android_15_16", "Android 15/16 rules", Support.SUPPORTED,
            "Foreground-service type and permission declarations, while-in-use microphone rule, per-session " +
                    "MediaProjection consent, startForeground(mediaProjection) before getMediaProjection.",
            "Built with targetSdk 36 and run on Android 16."),
        CaptureScenario("oem", "Manufacturer behaviour", Support.DEVICE_DEPENDENT,
            "OEMs may restrict background services, silence concurrent capture, or add their own call " +
                    "recorders that are not available to other apps.",
            "Observed only on the test device.")
    )

    fun scenario(id: String): CaptureScenario = scenarios.first { it.id == id }
}

/** What the platform can tell us right now, without any privileged access. */
interface CaptureEnvironment {
    val sdkInt: Int
    fun hasMicrophonePermission(): Boolean
    /** A phone or VoIP call currently holds the audio mode (AudioManager.getMode, no permission needed). */
    fun isCallActive(): Boolean
}

enum class BlockReason(val message: String) {
    MICROPHONE_PERMISSION(
        "Microphone access is off. Allow it to record, or nothing can be captured."),
    MICROPHONE_UNAVAILABLE(
        "The microphone is not available right now (another app or the system may be using it). " +
                "Nothing was recorded."),
    PLAYBACK_CAPTURE_UNSUPPORTED(
        "Recording audio from other apps needs Android 10 or newer. You can still record with the microphone."),
    PLAYBACK_CAPTURE_DENIED(
        "Permission to capture audio playing on this phone was not granted. Nothing was recorded."),
    PROTECTED_CALL_AUDIO(
        "Android does not let apps record phone or WhatsApp call audio, so this app cannot capture " +
                "both sides of a call. You can record what the microphone hears instead.")
}

enum class CaptureNotice(val message: String) {
    MICROPHONE_ONLY("Recording the microphone: what this phone hears."),
    PLAYBACK_ONLY("Recording audio played by other apps that allow it. Calls are never included."),
    CALL_ACTIVE_MICROPHONE(
        "A call appears to be active. Android may give apps silence during calls, and this app " +
                "cannot record the other side of phone or WhatsApp calls."),
    CALL_ACTIVE_PLAYBACK(
        "A call appears to be active. Call audio is protected by Android and will not be recorded."),
    SILENCED_BY_SYSTEM(
        "Android is not giving this app any audio right now (usually while a call is active). " +
                "Nothing useful is being recorded."),
    BACKGROUND_NOT_ALLOWED(
        "Android did not allow recording in the background. Keep this screen open while recording."),
    LIMIT_REACHED("Recording stopped at the 60-minute limit.")
}

sealed interface CaptureDecision {
    data class Allowed(val notices: List<CaptureNotice>) : CaptureDecision
    data class Blocked(val reason: BlockReason) : CaptureDecision
    /** Android's own consent dialog must be shown first (MediaProjection). */
    object NeedsSystemConsent : CaptureDecision
}

object CapturePolicy {

    const val PLAYBACK_CAPTURE_MIN_SDK = 29

    /** Before a capture starts. */
    fun decide(mode: CaptureMode, env: CaptureEnvironment,
               projectionGranted: Boolean? = null): CaptureDecision {
        if (!env.hasMicrophonePermission()) {
            // AudioPlaybackCapture is also gated on RECORD_AUDIO.
            return CaptureDecision.Blocked(BlockReason.MICROPHONE_PERMISSION)
        }
        val call = env.isCallActive()
        return when (mode) {
            CaptureMode.MICROPHONE -> CaptureDecision.Allowed(
                listOfNotNull(CaptureNotice.MICROPHONE_ONLY,
                              CaptureNotice.CALL_ACTIVE_MICROPHONE.takeIf { call }))
            CaptureMode.PLAYBACK -> when {
                env.sdkInt < PLAYBACK_CAPTURE_MIN_SDK ->
                    CaptureDecision.Blocked(BlockReason.PLAYBACK_CAPTURE_UNSUPPORTED)
                projectionGranted == null -> CaptureDecision.NeedsSystemConsent
                !projectionGranted -> CaptureDecision.Blocked(BlockReason.PLAYBACK_CAPTURE_DENIED)
                else -> CaptureDecision.Allowed(
                    listOfNotNull(CaptureNotice.PLAYBACK_ONLY,
                                  CaptureNotice.CALL_ACTIVE_PLAYBACK.takeIf { call }))
            }
        }
    }

    /**
     * The "record both sides of a call" request. Always refused, with an
     * explanation, because no supported API provides it to an ordinary app.
     */
    fun decideCallAudio(): CaptureDecision = CaptureDecision.Blocked(BlockReason.PROTECTED_CALL_AUDIO)

    /** Live notices while recording, from what the platform reports. */
    fun liveNotices(mode: CaptureMode, callActive: Boolean, silenced: Boolean,
                    backgroundAllowed: Boolean): List<CaptureNotice> = listOfNotNull(
        if (mode == CaptureMode.MICROPHONE) CaptureNotice.MICROPHONE_ONLY else CaptureNotice.PLAYBACK_ONLY,
        when {
            !callActive -> null
            mode == CaptureMode.MICROPHONE -> CaptureNotice.CALL_ACTIVE_MICROPHONE
            else -> CaptureNotice.CALL_ACTIVE_PLAYBACK
        },
        CaptureNotice.SILENCED_BY_SYSTEM.takeIf { silenced },
        CaptureNotice.BACKGROUND_NOT_ALLOWED.takeIf { !backgroundAllowed }
    )
}
