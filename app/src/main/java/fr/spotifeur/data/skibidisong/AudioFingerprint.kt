package fr.spotifeur.data.skibidisong

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * A 32-bit sub-fingerprint per frame, in the style of Haitsma and Kalker: the sign of the energy
 * difference between neighbouring bands, compared with the previous frame. Two encodings of the same
 * recording keep most bits; another recording or another version flips about half of them.
 */
class AudioFingerprint internal constructor(
    /** One sub-fingerprint per frame. */
    val bits: IntArray,
    /** Frames loud enough for their bits to mean something. */
    val active: BooleanArray,
) {
    val frames: Int get() = bits.size
    val activeFrames: Int get() = active.count { it }
    val durationMs: Long get() = frames * HOP_MS.toLong()

    /** Best alignment of [this] excerpt inside [other], and how many bits differ there. */
    data class Comparison(
        /** Bit error rate at the best lag: about 0.5 for unrelated audio. */
        val errorRate: Double,
        /** Where this excerpt starts in [other], in milliseconds (negative: before it starts). */
        val lagMs: Long,
        /** Frames compared at that lag. */
        val frames: Int,
        /** Average bit error rate over every lag tried, as a reference. */
        val meanErrorRate: Double,
    ) {
        fun matches(threshold: Double = MATCH_THRESHOLD): Boolean = frames >= MIN_OVERLAP_FRAMES && errorRate <= threshold
    }

    /**
     * Slides this excerpt over [other] from [minLagMs] to as far as the overlap allows, and keeps the
     * lag with the fewest differing bits among the active frames of this excerpt.
     */
    fun compare(other: AudioFingerprint, minLagMs: Long = -3_000): Comparison? {
        val minLag = (minLagMs / HOP_MS).toInt()
        val maxLag = other.frames - MIN_OVERLAP_FRAMES
        if (maxLag < minLag) return null
        val activeIndices = active.indices.filter { active[it] }.toIntArray()
        if (activeIndices.size < MIN_OVERLAP_FRAMES) return null
        var bestRate = 1.0
        var bestLag = 0
        var bestFrames = 0
        var sum = 0.0
        var tried = 0
        for (lag in minLag..maxLag) {
            var errors = 0
            var count = 0
            for (i in activeIndices) {
                val j = i + lag
                if (j < 0) continue
                if (j >= other.frames) break
                errors += Integer.bitCount(bits[i] xor other.bits[j])
                count++
            }
            if (count < MIN_OVERLAP_FRAMES) continue
            val rate = errors.toDouble() / (count * 32)
            sum += rate; tried++
            if (rate < bestRate) { bestRate = rate; bestLag = lag; bestFrames = count }
        }
        if (tried == 0) return null
        return Comparison(bestRate, (bestLag * HOP_MS).toLong(), bestFrames, sum / tried)
    }

    companion object {
        const val SAMPLE_RATE = 5_512
        const val FRAME = 2_048
        const val HOP = 64
        const val HOP_MS = HOP * 1000.0 / SAMPLE_RATE
        const val BANDS = 33
        const val MIN_HZ = 300.0
        const val MAX_HZ = 2_000.0
        /** About 1.4 s of audio: enough bits for an error rate far from chance. */
        const val MIN_OVERLAP_FRAMES = 120
        /** Measured on real pairs: same recording well below, other recordings around 0.45 and above. */
        const val MATCH_THRESHOLD = 0.35
        /** Frames this far under the usual level (in energy) are treated as silence. */
        private const val SILENCE_RATIO = 1e-3

        private val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        private val edges: IntArray = IntArray(BANDS + 1) { band ->
            val hz = exp(ln(MIN_HZ) + (ln(MAX_HZ) - ln(MIN_HZ)) * band / BANDS)
            (hz * FRAME / SAMPLE_RATE).toInt()
        }

        /** Mono samples in -1..1 at [sampleRate]. */
        fun of(samples: FloatArray, sampleRate: Int): AudioFingerprint = ofResampled(resample(samples, sampleRate))

        internal fun ofResampled(signal: DoubleArray): AudioFingerprint {
            val count = if (signal.size < FRAME) 0 else (signal.size - FRAME) / HOP + 1
            val energies = Array(count) { DoubleArray(BANDS) }
            val totals = DoubleArray(count)
            val re = DoubleArray(FRAME)
            val im = DoubleArray(FRAME)
            // Two real frames per complex transform: one in the real part, the next in the imaginary part.
            var frame = 0
            while (frame < count) {
                val second = frame + 1 < count
                val a = frame * HOP
                val b = (frame + 1) * HOP
                for (i in 0 until FRAME) {
                    re[i] = signal[a + i] * window[i]
                    im[i] = if (second) signal[b + i] * window[i] else 0.0
                }
                fft(re, im)
                val first = energies[frame]
                val next = if (second) energies[frame + 1] else null
                for (band in 0 until BANDS) {
                    var energyA = 0.0
                    var energyB = 0.0
                    for (bin in edges[band] until edges[band + 1]) {
                        val mirror = (FRAME - bin) and (FRAME - 1)
                        // A = (Z[k] + conj Z[N-k]) / 2, B = (Z[k] - conj Z[N-k]) / 2i
                        val ar = (re[bin] + re[mirror]) / 2; val ai = (im[bin] - im[mirror]) / 2
                        val br = (im[bin] + im[mirror]) / 2; val bi = (re[mirror] - re[bin]) / 2
                        energyA += ar * ar + ai * ai
                        energyB += br * br + bi * bi
                    }
                    first[band] = energyA; totals[frame] += energyA
                    if (next != null) { next[band] = energyB; totals[frame + 1] += energyB }
                }
                frame += 2
            }
            val bits = IntArray(count)
            for (frame in 1 until count) {
                val now = energies[frame]
                val before = energies[frame - 1]
                var word = 0
                for (band in 0 until 32) {
                    val difference = (now[band] - now[band + 1]) - (before[band] - before[band + 1])
                    if (difference > 0) word = word or (1 shl band)
                }
                bits[frame] = word
            }
            // Relative to the usual level, not to the loudest frame: one accent in a quiet intro
            // (a cymbal, a clock tick) would otherwise make the rest of it count as silence.
            val reference = totals.sorted().let { sorted -> sorted.getOrNull((sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)) } ?: 0.0
            val active = BooleanArray(count) { it > 0 && reference > 0 && totals[it] >= reference * SILENCE_RATIO }
            return AudioFingerprint(bits, active)
        }

        /** [fft] for [FRAME] points, with precomputed twiddle factors. */
        private fun fftFrame(re: DoubleArray, im: DoubleArray) {
            val n = FRAME
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var length = 2
            while (length <= n) {
                val half = length / 2
                val step = n / length
                var start = 0
                while (start < n) {
                    for (k in 0 until half) {
                        val cr = twiddleCos[k * step]
                        val ci = twiddleSin[k * step]
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * cr - im[b] * ci
                        val ti = re[b] * ci + im[b] * cr
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                    }
                    start += length
                }
                length = length shl 1
            }
        }

        /** Averages the input over each output period (a box low-pass), whatever the input rate. */
        internal fun resample(samples: FloatArray, sampleRate: Int): DoubleArray {
            if (samples.isEmpty() || sampleRate <= 0) return DoubleArray(0)
            val prefix = DoubleArray(samples.size + 1)
            for (i in samples.indices) prefix[i + 1] = prefix[i] + samples[i]
            val ratio = sampleRate.toDouble() / SAMPLE_RATE
            val length = (samples.size / ratio).toInt()
            return DoubleArray(length) { i ->
                val from = (i * ratio).toInt().coerceIn(0, samples.size - 1)
                val to = ((i + 1) * ratio).toInt().coerceIn(from + 1, samples.size)
                (prefix[to] - prefix[from]) / (to - from)
            }
        }

        private val twiddleCos = DoubleArray(FRAME / 2) { cos(-2 * PI * it / FRAME) }
        private val twiddleSin = DoubleArray(FRAME / 2) { sin(-2 * PI * it / FRAME) }

        /** In-place radix-2 FFT; the length must be a power of two. */
        internal fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            if (n == FRAME) { fftFrame(re, im); return }
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2 * PI / length
                val wr = cos(angle)
                val wi = sin(angle)
                var start = 0
                while (start < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until length / 2) {
                        val a = start + k
                        val b = a + length / 2
                        val tr = re[b] * cr - im[b] * ci
                        val ti = re[b] * ci + im[b] * cr
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                        val next = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = next
                    }
                    start += length
                }
                length = length shl 1
            }
        }
    }
}
