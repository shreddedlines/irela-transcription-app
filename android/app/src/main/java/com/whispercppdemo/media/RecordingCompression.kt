package com.whispercppdemo.media

/**
 * How a finished microphone recording is compressed before a CLOUD upload.
 *
 * The recorder writes 16 kHz mono 16-bit WAV: 32 kB/s, so 60 minutes is
 * 115.2 MB and would hit the 100 MB upload cap at about 54.6 minutes. The
 * product supports 60-minute recordings, so the cloud path uploads this
 * instead. The LOCAL engine keeps reading the WAV exactly as before.
 *
 * AAC-LC because every Android device since API 16 ships an encoder for it
 * (Opus encoding needs API 29), MediaMuxer writes it natively, Deepgram accepts
 * it, and the backend reads its exact duration from the MP4 header.
 *
 * 64 kbit/s mono at the recorder's own 16 kHz: no resampling, so the audio
 * bandwidth (8 kHz) is exactly what the microphone captured, and 64 kbit/s is
 * about 4 bits per sample for a single 8 kHz-band voice channel -- far above
 * the rate where AAC-LC starts to smear consonants. Speech intelligibility,
 * which is what Hindi/Hinglish recognition depends on, is not the constraint
 * at this rate; the 8 kHz band limit of the recording itself is.
 *
 * Pure Kotlin so the size arithmetic is testable on the JVM.
 */
object RecordingCompression {
    const val CODEC_MIME = "audio/mp4a-latm"        // MediaFormat.MIMETYPE_AUDIO_AAC
    const val UPLOAD_MIME = "audio/mp4"
    const val EXTENSION = "m4a"
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BIT_RATE = 64_000

    /** AAC-LC codes whole frames of this many samples. */
    const val SAMPLES_PER_FRAME = 1024

    /**
     * A generous upper bound on the compressed size.
     *
     * Payload at the nominal bitrate with 15% allowance for encoder
     * overshoot, plus MP4 sample tables at 16 bytes per frame (real tables
     * need about 8) and 64 KB of fixed boxes.
     */
    fun upperBoundBytes(seconds: Long): Long {
        val payload = seconds * BIT_RATE / 8
        val frames = (seconds * SAMPLE_RATE + SAMPLES_PER_FRAME - 1) / SAMPLES_PER_FRAME
        return payload * 115 / 100 + frames * 16 + 64 * 1024
    }

    /** Size of the recorder's uncompressed WAV for [seconds] of audio. */
    fun wavBytes(seconds: Long): Long = 44 + seconds * SAMPLE_RATE * 2L * CHANNELS
}
