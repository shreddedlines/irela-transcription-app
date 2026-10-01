package com.whispercppdemo.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val LOG_TAG = "APEXTRACE"

/**
 * End-to-end stage timing for one transcription run.
 *
 * Process-wide and deliberately outside the Activity, for the same reason
 * TranscriptionStore is: the run being measured outlives the UI. A run starts
 * at the moment the share/VIEW intent lands and ends when the History reload
 * that follows persistence has completed, so every stage the user waits on is
 * inside one clock.
 *
 * Everything is emitted as single-line `APEXTRACE|` records so one logcat
 * filter captures a whole run and the result is machine-parseable:
 *
 *     adb logcat -s APEXTRACE:I
 *
 * Measurement only. Nothing here influences decoding, chunking or sampling.
 */
object RunTrace {

    /** Stage names, fixed so the report always has the same rows. */
    object Stage {
        const val MODEL_LOAD = "0_model_load"
        const val INTENT = "1_intent_received"
        const val URI_RESOLVE = "2_uri_resolution"
        const val STAGE_COPY = "3_source_copy"
        const val DECODE = "4_audio_decode"
        const val RESAMPLE = "5_resample"
        const val CHUNK_PLAN = "6_chunk_plan"
        const val INFERENCE = "7_inference"
        const val FALLBACK = "8_fallback_redecode"
        const val SEAM = "9_seam_join"
        const val PERSIST = "10_transcript_persist"
        const val HISTORY_WRITE = "11_history_write"
        const val HISTORY_READ = "12_history_refresh"
    }

    @Volatile
    private var runId: String = "none"

    @Volatile
    private var runStartMs: Long = 0L

    /** Accumulated wall time per stage, so repeated stages (per-chunk) sum. */
    private val totals = ConcurrentHashMap<String, AtomicLong>()

    /** Open stage start times, keyed by stage name. */
    private val open = ConcurrentHashMap<String, Long>()

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    private var peakPssKb: Long = 0

    /** Thermal samples as "elapsedMs=temperatureC" in capture order. */
    private val thermal = java.util.Collections.synchronizedList(mutableListOf<String>())

    // ---- run lifecycle ----------------------------------------------------

    /** Begins a run. Called the instant an intent or a record action arrives. */
    fun begin(id: String, note: String) {
        runId = id
        runStartMs = System.currentTimeMillis()
        totals.clear(); open.clear(); counters.clear(); thermal.clear()
        peakPssKb = 0
        Diag.d(LOG_TAG, "RUN_BEGIN|run=$id|note=$note|wallClock=$runStartMs")
        sampleThermal("start")
    }

    fun elapsed(): Long =
        if (runStartMs == 0L) 0L else System.currentTimeMillis() - runStartMs

    // ---- stage timing -----------------------------------------------------

    fun stageStart(stage: String) {
        open[stage] = System.currentTimeMillis()
        Diag.d(LOG_TAG, "STAGE_START|run=$runId|stage=$stage|at=${elapsed()}")
    }

    /** Ends an open stage and returns its duration, or -1 if it was not open. */
    fun stageEnd(stage: String, detail: String = ""): Long {
        val started = open.remove(stage) ?: return -1
        val dt = System.currentTimeMillis() - started
        add(stage, dt)
        Diag.d(LOG_TAG, "STAGE_END|run=$runId|stage=$stage|ms=$dt|at=${elapsed()}|$detail")
        return dt
    }

    /** Adds [ms] to a stage total without opening/closing it. */
    fun add(stage: String, ms: Long) {
        totals.getOrPut(stage) { AtomicLong(0) }.addAndGet(ms)
    }

    fun count(name: String, by: Long = 1) {
        counters.getOrPut(name) { AtomicLong(0) }.addAndGet(by)
    }

    fun set(name: String, value: Long) {
        counters.getOrPut(name) { AtomicLong(0) }.set(value)
    }

    // ---- named checkpoints the brief asks for -----------------------------

    fun transcriptionStarted(name: String?, samples: Int, sampleRate: Int) {
        Diag.d(
            LOG_TAG, "TRANSCRIPTION_STARTED|run=$runId|file=$name" +
                    "|audioMs=${samples * 1000L / sampleRate}|at=${elapsed()}"
        )
    }

    fun transcriptionCompleted(chars: Int, words: Int) {
        Diag.d(LOG_TAG, "TRANSCRIPTION_COMPLETED|run=$runId|chars=$chars|words=$words|at=${elapsed()}")
    }

    fun persistBegin(source: String) {
        Diag.d(LOG_TAG, "TRANSCRIPT_PERSIST_BEGIN|run=$runId|source=$source|at=${elapsed()}")
    }

    fun persistSuccess(recordId: String, path: String, bytes: Long) {
        Diag.d(
            LOG_TAG, "TRANSCRIPT_PERSIST_SUCCESS|run=$runId|record=$recordId" +
                    "|path=$path|bytes=$bytes|at=${elapsed()}"
        )
    }

    fun persistSkipped(reason: String) {
        Diag.w(LOG_TAG, "TRANSCRIPT_PERSIST_SKIPPED|run=$runId|reason=$reason|at=${elapsed()}")
    }

    fun persistFailed(reason: String) {
        Diag.w(LOG_TAG, "TRANSCRIPT_PERSIST_FAILED|run=$runId|reason=$reason|at=${elapsed()}")
    }

    fun historyRefresh(count: Int, ms: Long, caller: String) {
        Diag.d(LOG_TAG, "HISTORY_REFRESH|run=$runId|records=$count|ms=$ms|caller=$caller|at=${elapsed()}")
    }

    // ---- device state -----------------------------------------------------

    /**
     * Highest thermal-zone reading in Celsius, or -1 when the zones are not
     * readable by an unprivileged app (some OEM builds restrict them; the
     * adb-side sampler in the harness is the fallback).
     */
    fun readMaxTempC(): Int = runCatching {
        File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.mapNotNull { z ->
                runCatching { File(z, "temp").readText().trim().toLong() }.getOrNull()
            }
            ?.maxOrNull()
            ?.let { if (it > 1000) (it / 1000).toInt() else it.toInt() }
            ?: -1
    }.getOrDefault(-1)

    /** Current per-core scaling frequency in kHz, empty when unreadable. */
    fun readCpuKhz(): List<Long> = runCatching {
        File("/sys/devices/system/cpu").listFiles()
            ?.filter { it.name.matches(Regex("cpu\\d+")) }
            ?.sortedBy { it.name }
            ?.map { c ->
                runCatching {
                    File(c, "cpufreq/scaling_cur_freq").readText().trim().toLong()
                }.getOrDefault(-1L)
            }
            ?: emptyList()
    }.getOrDefault(emptyList())

    fun sampleThermal(label: String) {
        val t = readMaxTempC()
        val khz = readCpuKhz()
        thermal.add("${elapsed()}=$t")
        Diag.d(
            LOG_TAG, "THERMAL|run=$runId|label=$label|at=${elapsed()}|tempC=$t" +
                    "|cpuKhz=${khz.joinToString(",")}"
        )
    }

    fun samplePss(context: Context) {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val totalKb = mi.totalPss.toLong()
        if (totalKb > peakPssKb) peakPssKb = totalKb
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val heapKb = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 }
        Diag.d(
            LOG_TAG, "MEM|run=$runId|at=${elapsed()}|pssKb=$totalKb|peakPssKb=$peakPssKb" +
                    "|jvmHeapKb=$heapKb|largeHeapMb=${am?.largeMemoryClass ?: -1}"
        )
    }

    // ---- report -----------------------------------------------------------

    /** Emits the full per-stage breakdown. Called once, at the end of a run. */
    fun report(extra: String = "") {
        val total = elapsed()
        Diag.d(LOG_TAG, "RUN_REPORT|run=$runId|totalWallMs=$total|$extra")
        listOf(
            Stage.MODEL_LOAD, Stage.INTENT, Stage.URI_RESOLVE, Stage.STAGE_COPY,
            Stage.DECODE, Stage.RESAMPLE, Stage.CHUNK_PLAN, Stage.INFERENCE,
            Stage.FALLBACK, Stage.SEAM, Stage.PERSIST, Stage.HISTORY_WRITE,
            Stage.HISTORY_READ
        ).forEach { s ->
            val ms = totals[s]?.get() ?: 0L
            val pct = if (total > 0) ms * 100.0 / total else 0.0
            Diag.d(LOG_TAG, "STAGE_TOTAL|run=$runId|stage=$s|ms=$ms|pct=${"%.1f".format(pct)}")
        }
        counters.toSortedMap().forEach { (k, v) ->
            Diag.d(LOG_TAG, "COUNTER|run=$runId|name=$k|value=${v.get()}")
        }
        Diag.d(LOG_TAG, "THERMAL_SERIES|run=$runId|${thermal.joinToString(",")}")
        Diag.d(LOG_TAG, "PEAK_MEM|run=$runId|peakPssKb=$peakPssKb")
        Diag.d(LOG_TAG, "RUN_END|run=$runId|totalWallMs=$total")
    }
}
