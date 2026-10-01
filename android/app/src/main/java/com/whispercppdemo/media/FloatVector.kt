package com.whispercppdemo.media

/**
 * Growable primitive float buffer.
 *
 * Used instead of ArrayList<Float> because imported audio can run to tens of
 * millions of samples and boxing every one would blow the 256 MB Java heap
 * long before the audio itself did.
 */
internal class FloatVector(initialCapacity: Int = 16 * 1024) {
    private var buf = FloatArray(initialCapacity.coerceAtLeast(16))
    var size: Int = 0
        private set

    fun append(value: Float) {
        ensure(size + 1)
        buf[size++] = value
    }

    fun append(src: FloatArray, from: Int = 0, count: Int = src.size - from) {
        if (count <= 0) return
        ensure(size + count)
        System.arraycopy(src, from, buf, size, count)
        size += count
    }

    /**
     * Pre-allocates for [capacity] samples.
     *
     * Long imports live or die on this. Without it the buffer reaches its
     * final size by repeated 1.5x growth, so a 30-minute decode held a 172 MB
     * backing array while [toFloatArray] tried to allocate its own 115 MB
     * copy -- together past the 268 MB heap limit, and the import died with
     * OutOfMemoryError before a single chunk was transcribed.
     */
    fun reserve(capacity: Int) {
        if (capacity > buf.size) buf = buf.copyOf(capacity)
    }

    private fun ensure(capacity: Int) {
        if (capacity <= buf.size) return
        var next = buf.size
        while (next < capacity) {
            // 1.5x growth while the buffer is small, but a bounded step once
            // it is large: at 100 MB the difference between 1.5x and a fixed
            // step is tens of megabytes of headroom on a 268 MB heap.
            val step = if (next >= LARGE) LARGE_STEP else (next shr 1).coerceAtLeast(1)
            next += step
            if (next < 0) next = capacity   // overflow guard
        }
        buf = buf.copyOf(next)
    }

    /**
     * The contents as a FloatArray, WITHOUT copying when the buffer is exactly
     * full.
     *
     * Paired with [reserve], a decode that predicted its length correctly
     * hands the backing array straight over and never holds two copies of a
     * long recording at once. The vector must not be appended to afterwards,
     * which suits its one caller: decode finishes, then hands off.
     */
    fun detach(): FloatArray = if (size == buf.size) buf else buf.copyOf(size)

    /** Exact-length copy of the contents. */
    fun toFloatArray(): FloatArray = buf.copyOf(size)

    private companion object {
        /** Beyond this the growth factor costs more than it saves. */
        const val LARGE = 32 * 1024 * 1024      // 32 M floats = 128 MB
        const val LARGE_STEP = 2 * 1024 * 1024  // grow by 8 MB at a time
    }
}
