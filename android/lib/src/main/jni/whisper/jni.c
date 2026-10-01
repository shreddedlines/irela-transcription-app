#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"
#include "ggml-cpu.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

// ---- Diagnostics: temperature fallback / re-decode ------------------------
// whisper.cpp reports its temperature-fallback counters through
// whisper_print_timings, which goes to the default log callback -- i.e. stderr,
// which Android discards. Bridging the callback to logcat is what makes the
// line "fallbacks = N p / N h" observable on device, and it is the only direct
// evidence of how much of a run is re-decoding the same window at a higher
// temperature. Logging only: no decoding parameter is touched.
static void whisper_log_to_logcat(enum ggml_log_level level, const char * text, void * user_data) {
    UNUSED(user_data);
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) {
        prio = ANDROID_LOG_ERROR;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        prio = ANDROID_LOG_WARN;
    }
    __android_log_print(prio, "WHISPER", "%s", text);
}

// ---- Diagnostics: engine identification ------------------------------------
// Emits the capability flags the LOADED binary actually has. ggml decides these
// at compile time from -march, so this is the only statement about SIMD support
// that cannot be wrong: it comes from the .so that is executing.
static void log_engine_info(void) {
    whisper_log_set(whisper_log_to_logcat, NULL);
    __android_log_print(ANDROID_LOG_INFO, "APEXTRACE",
        "ENGINE|system_info=%s", whisper_print_system_info());
    __android_log_print(ANDROID_LOG_INFO, "APEXTRACE",
        "ENGINE_FLAGS|NEON=%d|ARM_FMA=%d|FP16_VA=%d|MATMUL_INT8=%d|DOTPROD=%d|SVE=%d",
        ggml_cpu_has_neon(), ggml_cpu_has_arm_fma(), ggml_cpu_has_fp16_va(),
        ggml_cpu_has_matmul_int8(), ggml_cpu_has_dotprod(), ggml_cpu_has_sve());
}


static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercppdemo_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    context = whisper_init(&loader);
    return (jlong) context;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    log_engine_info();
    context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    // The below adapted from the Objective-C iOS sample
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = true;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    // Fixed decoding configuration, validated on desktop before shipping:
    //   Apex q4_K GGML, -l hi, -bs 1 (== WHISPER_SAMPLING_GREEDY), -bo 5,
    //   -et 2.40, -lpt -1.00, -nth 0.60, -tpi 0.20
    params.language = "hi";
    params.greedy.best_of = 5;
    params.entropy_thold = 2.40f;
    params.logprob_thold = -1.00f;
    params.no_speech_thold = 0.60f;
    params.temperature_inc = 0.20f;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGI("Failed to run the model");
    } else {
        // Diagnostics. encode_ms is one pass over the 30 s window;
        // decode_ms accumulates every temperature attempt, so decode_ms rising
        // while encode_ms stays flat is exactly the fallback cost.
        struct whisper_timings * tm = whisper_get_timings(context);
        if (tm != NULL) {
            LOGI("TIMINGS|sample_ms=%.2f|encode_ms=%.2f|decode_ms=%.2f|batchd_ms=%.2f|prompt_ms=%.2f|segments=%d",
                 (double) tm->sample_ms, (double) tm->encode_ms, (double) tm->decode_ms,
                 (double) tm->batchd_ms, (double) tm->prompt_ms,
                 whisper_full_n_segments(context));
        }
        whisper_print_timings(context);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}


// ---- Configurable decode (gated recovery) ----------------------------------
// Identical to fullTranscribe except that sampling strategy and temperature are
// caller-supplied. Production's fullTranscribe above is byte-for-byte untouched;
// with beam_size<=1 and temperature 0.0 this reproduces it exactly, which is
// verified before any retry result is trusted.
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_probeTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jint beam_size, jfloat temperature,
        jint best_of, jfloat temp_inc, jfloat entropy_thold, jfloat logprob_thold) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(
        beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = true;
    params.print_progress   = false;
    params.print_timestamps = true;
    params.print_special    = false;
    params.translate        = false;
    params.language         = "hi";
    params.greedy.best_of   = best_of;
    if (beam_size > 1) params.beam_search.beam_size = beam_size;
    params.entropy_thold    = entropy_thold;
    params.logprob_thold    = logprob_thold;
    params.no_speech_thold  = 0.60f;
    params.temperature      = temperature;
    params.temperature_inc  = temp_inc;
    params.n_threads        = num_threads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;

    whisper_reset_timings(context);
    if (whisper_full(context, params, audio, n) != 0) {
        (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);
        return -1;
    }
    whisper_print_timings(context);   /* same reporting as the production path */
    int ns = whisper_full_n_segments(context), ntok = 0;
    double psum = 0.0, nsp = 0.0;
    for (int i = 0; i < ns; i++) {
        int nt = whisper_full_n_tokens(context, i);
        for (int j = 0; j < nt; j++) psum += whisper_full_get_token_p(context, i, j);
        ntok += nt;
        nsp += whisper_full_get_segment_no_speech_prob(context, i);
    }
    LOGI("PROBE|segments=%d|tokens=%d|mean_p=%.4f|beam=%d|bo=%d|tinc=%.2f|et=%.2f|lpt=%.1f",
         ns, ntok, ntok ? psum / ntok : 0.0, (int) beam_size, (int) best_of,
         (double) temp_inc, (double) entropy_thold, (double) logprob_thold);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);
    return ntok;
}
// ---- End of configurable decode ---------------------------------------------

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

/*
 * Segment text is copied out as raw BYTES, never turned into a jstring here.
 *
 * NewStringUTF requires valid Modified UTF-8 and, under CheckJNI, aborts the
 * whole process on anything else. whisper segments are cut at token boundaries,
 * so a multi-byte Devanagari character can straddle two segments; the first
 * segment then ends mid-character and NewStringUTF killed the app:
 *   "input is not valid Modified UTF-8: illegal continuation byte 0x20".
 * Decoding happens in Kotlin (Utf8Segments), which sees adjacent segments,
 * stitches split characters, and replaces genuinely malformed bytes.
 */
static jbyteArray segment_bytes(JNIEnv *env, const char *text) {
    jsize len = text != NULL ? (jsize) strlen(text) : 0;
    jbyteArray bytes = (*env)->NewByteArray(env, len);
    if (bytes != NULL && len > 0) {
        (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte *) text);
    }
    return bytes;
}

JNIEXPORT jbyteArray JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentBytes(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return segment_bytes(env, whisper_full_get_segment_text(context, index));
}

/*
 * Kept for any caller of the old API, but no longer able to abort: the bytes
 * go through java.lang.String(byte[], "UTF-8"), whose decoder substitutes
 * U+FFFD for malformed input. It cannot stitch a character split across
 * segments -- getTextSegmentBytes + Utf8Segments does that.
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jbyteArray bytes = segment_bytes(env, whisper_full_get_segment_text(context, index));
    if (bytes == NULL) return NULL;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jmethodID ctor = (*env)->GetMethodID(env, string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");   /* ASCII literal: always valid */
    jstring string = (jstring) (*env)->NewObject(env, string_class, ctor, bytes, charset);
    (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, string_class);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                          jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
