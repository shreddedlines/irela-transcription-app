package com.whispercppdemo.transcribe

import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.diag.RunTrace
import com.whispercppdemo.ui.main.overlapTokens
import com.whispercppdemo.ui.main.planChunks

private const val LOG_TAG = "MainScreenViewModel"   // keep SEAM| log tag stable

// ---- validated chunking architecture -------------------------------
// 30 s pieces, 28 s stride, 2 s lead-in overlap, seam dedup. LOCKED.
const val SAMPLE_RATE = 16000
private const val CHUNK_SECONDS = 30
private const val OVERLAP_SECONDS = 2

/**
 * The locked transcription loop, lifted out of MainScreenViewModel unchanged
 * so that both the ViewModel (recording path) and the foreground service
 * (import path) run the identical algorithm instead of two copies.
 *
 * Nothing about the chunk planning, seam dedup or segment joining differs
 * from the validated version; only the reporting is now a callback rather
 * than direct UI state, so a service with no UI can drive it.
 */
suspend fun transcribeChunked(
    whisperContext: WhisperContext,
    data: FloatArray,
    onMessage: (String) -> Unit,
    onChunk: (index: Int, total: Int, startedAtMs: Long) -> Unit = { _, _, _ -> }
): String {
    val chunkLen = CHUNK_SECONDS * SAMPLE_RATE
    val overlapLen = OVERLAP_SECONDS * SAMPLE_RATE
    val total = data.size

    val minTailLen = SAMPLE_RATE / 10   // 100 ms
    RunTrace.stageStart(RunTrace.Stage.CHUNK_PLAN)
    val pieces = planChunks(total, chunkLen, overlapLen, minTailLen)
    RunTrace.stageEnd(RunTrace.Stage.CHUNK_PLAN, "chunks=${pieces.size}")
    RunTrace.set("chunks", pieces.size.toLong())
    RunTrace.set("audio_ms", total * 1000L / SAMPLE_RATE)

    onMessage("Chunked: ${pieces.size} x ${CHUNK_SECONDS}s (+${OVERLAP_SECONDS}s overlap)\n")
    val runStart = System.currentTimeMillis()
    var merged = mutableListOf<String>()
    var dupTotal = 0

    for ((i, span) in pieces.withIndex()) {
        onChunk(i + 1, pieces.size, runStart)
        val (s, e) = span
        val slice = data.copyOfRange(s, e)
        val t0 = System.currentTimeMillis()
        val piece = whisperContext.transcribeData(slice, printTimestamp = false)
        val dt = System.currentTimeMillis() - t0
        // Stage 7, per chunk. rtf is this chunk's inference time against the
        // audio it covers, so a thermally throttled run shows up as rtf
        // climbing across chunks rather than as one bad total.
        RunTrace.add(RunTrace.Stage.INFERENCE, dt)
        val chunkAudioMs = (e - s) * 1000L / SAMPLE_RATE
        Diag.d("APEXTRACE", "CHUNK|index=${i + 1}/${pieces.size}|ms=$dt" +
                "|audioMs=$chunkAudioMs|rtf=${"%.3f".format(dt.toDouble() / chunkAudioMs)}" +
                "|tempC=${RunTrace.readMaxTempC()}|at=${RunTrace.elapsed()}")
        RunTrace.sampleThermal("chunk${i + 1}")

        // Stage 9: seam detection and merge, measured apart from inference so
        // it can be ruled in or out rather than assumed negligible.
        val seam0 = System.currentTimeMillis()
        val toks = piece.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val dup = if (merged.isEmpty()) 0 else overlapTokens(merged, toks)
        RunTrace.add(RunTrace.Stage.SEAM, System.currentTimeMillis() - seam0)

        // ---- DIAGNOSTIC ONLY, debug builds, NO TRANSCRIPT TEXT ----
        // The previous version logged `raw=`, `prevTail16=` and `newHead16=`,
        // which put the user's actual words into logcat. Seam behaviour is
        // fully diagnosable from the counts, so the text is gone rather than
        // truncated: a truncated transcript is still a transcript.
        Diag.d(LOG_TAG, "SEAM|chunk=${i + 1}/${pieces.size}|span=${s}-${e}" +
                "|sec=${"%.3f".format(s / 1000.0 / 16)}-${"%.3f".format(e / 1000.0 / 16)}" +
                "|prevWords=${merged.size}|newWords=${toks.size}|dedup=$dup")
        // ---- END DIAGNOSTIC ---------------------------------------

        dupTotal += dup
        merged.addAll(toks.drop(dup))

        val rssKb = Runtime.getRuntime().let {
            (it.totalMemory() - it.freeMemory()) / 1024
        }
        onMessage(
            "  chunk ${i + 1}/${pieces.size} " +
                    "[${s / SAMPLE_RATE}-${e / SAMPLE_RATE}s, ${(e - s) * 1000L / SAMPLE_RATE}ms] " +
                    "${dt} ms, ${toks.size}w, dedup $dup, jvmHeap ${rssKb}kB\n"
        )
    }

    // No post-processing. A glossary substitution pass (A3) was integrated and
    // then removed: on the first genuinely new recording it made three edits,
    // all harmful -- `internet` -> `interns`, `registrations` -> `registration`,
    // `remotely` -> `remote`. Its guards stop short tokens and Hindi function
    // words from colliding with glossary terms, but not a correctly transcribed
    // word that happens to fall within the distance threshold of one, which is
    // what an inflected form of a glossary term always is. The merged tokens go
    // out as the transcript.
    val elapsed = System.currentTimeMillis() - runStart
    val seamJoin0 = System.currentTimeMillis()
    val text = merged.joinToString(" ")
    RunTrace.add(RunTrace.Stage.SEAM, System.currentTimeMillis() - seamJoin0)
    RunTrace.set("dedup_tokens", dupTotal.toLong())
    RunTrace.set("words", merged.size.toLong())
    RunTrace.set("inference_wall_ms", elapsed)
    onMessage(
        "DONE total ${elapsed} ms, ${merged.size} words, " +
                "${dupTotal} dup tokens removed\n\n$text\n"
    )
    return text
}
