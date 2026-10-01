package com.whispercppdemo.ui.common

import com.whispercppdemo.transcribe.Engine

/**
 * What the app tells the user about where their audio goes.
 *
 * One source of truth, because the app previously said "Audio stays on your
 * phone" on Home and "Processed entirely on this device" in History with both
 * strings hardcoded -- so a cloud build stated, twice, something that was no
 * longer true. A privacy claim is not decoration; it must be derived from the
 * engine that will actually run.
 *
 * Every user-visible statement about audio handling comes from here.
 */
object PrivacyCopy {

    /** The Home footer and the History chip. */
    fun audioHandling(engine: Engine): String = when (engine) {
        Engine.LOCAL -> "Audio stays on your phone."
        Engine.CLOUD -> "Audio is sent to the transcription service for transcription."
    }

    /**
     * The short Home line (Concept A). Still engine-derived: the full cloud
     * facts live on the first-use disclosure; this only says where audio goes
     * and where transcripts stay (CloudDisclosure.RETENTION_NOTE).
     */
    fun homeLine(engine: Engine): String = when (engine) {
        Engine.LOCAL -> "Transcribed on this phone."
        Engine.CLOUD -> "Transcribed online, saved on this phone."
    }

    /**
     * The microphone permission rationale. Asked before recording, so it must
     * describe what will happen to the recording afterwards.
     */
    fun microphoneRationale(engine: Engine): String = when (engine) {
        Engine.LOCAL ->
            "Recording uses your microphone. " + audioHandling(Engine.LOCAL)
        Engine.CLOUD ->
            "Recording uses your microphone. " + audioHandling(Engine.CLOUD)
    }

    /** The Processing screen heading while the engine is working. */
    fun transcribingHeading(engine: Engine): String = when (engine) {
        Engine.LOCAL -> "Transcribing locally"
        Engine.CLOUD -> "Transcribing in the cloud"
    }
}
