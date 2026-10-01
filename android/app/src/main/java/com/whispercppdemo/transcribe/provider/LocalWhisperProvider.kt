package com.whispercppdemo.transcribe.provider

import android.content.Context
import com.whispercppdemo.media.ImportError
import com.whispercppdemo.media.decodeToPcm16kMono
import com.whispercppdemo.transcribe.SAMPLE_RATE
import com.whispercppdemo.transcribe.TranscriptionState
import com.whispercppdemo.transcribe.WhisperEngine
import com.whispercppdemo.transcribe.transcribeChunked
import kotlinx.coroutines.CancellationException

/**
 * The existing on-device Apex engine, behind the provider interface.
 *
 * This is what lets one job pipeline drive both engines: JobRunner does not
 * know whether a transcript came from the device or from the network, so
 * switching to cloud in Phase D is a provider swap rather than a rewrite of the
 * lifecycle. It also means the local path inherits persistence, orphan
 * recovery, bounded retry and cancellation for free.
 *
 * Measured characteristics (kept here so the trade-off is not forgotten):
 * 6 min 35 s for 3 min 52 s of audio, 80 C peak, 820 MB PSS. Phase D makes
 * cloud the default and puts this behind a disabled flag; it is not deleted.
 */
class LocalWhisperProvider(
    private val context: Context,
    /**
     * Finer-grained progress than the generic interface can express -- decode
     * percentage and chunk counts. The provider contract carries a single
     * 0..1 fraction, which is right for an upload but would throw away real
     * information here, and inventing a number is exactly what we forbid.
     */
    private val onStage: (TranscriptionState) -> Unit = {}
) : TranscriptionProvider {

    override val id = "apex-local"
    override val displayName = "On-device (Apex)"

    override suspend fun transcribe(
        request: TranscriptionRequest,
        onProgress: (Float) -> Unit
    ): TranscriptionOutcome {
        val name = request.audio.name
        return try {
            // There is no upload. Report the send as instantly complete so the
            // job moves to TRANSCRIBING rather than sitting in a fictitious
            // UPLOADING state.
            onProgress(1f)

            onStage(TranscriptionState.Decoding(name, 0f))
            val pcm = decodeToPcm16kMono(request.audio) { p ->
                onStage(TranscriptionState.Decoding(name, p))
            }

            onStage(TranscriptionState.Transcribing(name, 0, 0))
            val text = WhisperEngine.use(context) { ctx ->
                transcribeChunked(
                    whisperContext = ctx,
                    data = pcm,
                    onMessage = { },
                    onChunk = { i, n, startedAt ->
                        onStage(TranscriptionState.Transcribing(name, i, n, startedAt))
                    }
                )
            }
            TranscriptionOutcome.Success(
                text = text, providerId = id,
                // Decoded samples at 16 kHz: the real length of the audio.
                audioDurationMs = pcm.size * 1000L / com.whispercppdemo.media.TARGET_SAMPLE_RATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportError) {
            // Bad audio is terminal: retrying a corrupt file cannot help.
            TranscriptionOutcome.Failure(
                FailureKind.TERMINAL_INPUT,
                e.message ?: "That audio could not be read.",
                providerId = id
            )
        } catch (e: OutOfMemoryError) {
            TranscriptionOutcome.Failure(
                FailureKind.TERMINAL_INPUT,
                "That recording is too large to transcribe on this device.",
                providerId = id
            )
        } catch (e: Exception) {
            // Engine faults are treated as retryable: a second pass over the
            // same PCM sometimes succeeds, and the retry bound stops a loop.
            TranscriptionOutcome.Failure(
                FailureKind.RETRYABLE_SERVER,
                e.localizedMessage ?: "Transcription failed.",
                providerId = id
            )
        }
    }
}
