package fr.spotifeur.data

import fr.spotifeur.data.skibidisong.AudioFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class AudioFingerprintTest {
    /** Notes of random pitch and length with a decaying envelope, like a simple melody. */
    private fun melody(seed: Int, seconds: Double, rate: Int, offsetSeconds: Double = 0.0, noise: Double = 0.0, gain: Double = 1.0): FloatArray {
        val random = Random(seed)
        val notes = ArrayList<Triple<Double, Double, Double>>()
        var time = -5.0
        while (time < seconds + offsetSeconds + 1) {
            val length = 0.15 + random.nextDouble() * 0.45
            notes += Triple(time, length, 220.0 * Math.pow(2.0, random.nextInt(0, 30) / 12.0))
            time += length
        }
        val noiseSource = Random(seed * 31 + 7)
        return FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate + offsetSeconds
            val note = notes.last { it.first <= t }
            val local = t - note.first
            val envelope = Math.exp(-3 * local)
            val tone = sin(2 * PI * note.third * t) + 0.5 * sin(2 * PI * note.third * 2 * t) + 0.25 * sin(2 * PI * note.third * 3 * t)
            (gain * 0.3 * envelope * tone + noise * (noiseSource.nextDouble() * 2 - 1)).toFloat()
        }
    }

    @Test fun sameRecordingAtAnotherRateAndOffsetMatches() {
        val spotify = AudioFingerprint.of(melody(1, 9.0, 44_100), 44_100)
        // The other service starts 1.5 s earlier in the recording, at 48 kHz, quieter and with noise.
        val youtube = AudioFingerprint.of(melody(1, 18.0, 48_000, offsetSeconds = -1.5, noise = 0.01, gain = 0.6), 48_000)
        val result = spotify.compare(youtube)
        assertNotNull(result)
        result!!
        assertTrue("error rate ${result.errorRate}", result.matches())
        assertTrue("lag ${result.lagMs}", abs(result.lagMs - 1_500) < 60)
    }

    @Test fun anotherRecordingDoesNotMatch() {
        val spotify = AudioFingerprint.of(melody(1, 9.0, 44_100), 44_100)
        val youtube = AudioFingerprint.of(melody(2, 18.0, 48_000), 48_000)
        val result = spotify.compare(youtube)!!
        assertFalse("error rate ${result.errorRate}", result.matches())
        assertTrue(result.meanErrorRate > 0.4)
    }

    @Test fun silenceIsNotComparable() {
        val silent = AudioFingerprint.of(FloatArray(9 * 44_100), 44_100)
        val youtube = AudioFingerprint.of(melody(2, 18.0, 48_000), 48_000)
        assertEquals(null, silent.compare(youtube))
    }

    @Test fun fftFindsASingleTone() {
        val n = 64
        val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) }
        val im = DoubleArray(n)
        AudioFingerprint.fft(re, im)
        val peak = (0 until n / 2).maxByOrNull { re[it] * re[it] + im[it] * im[it] }
        assertEquals(5, peak)
    }

    @Test fun frameTransformMatchesTheGenericOne() {
        val n = AudioFingerprint.FRAME
        val random = Random(3)
        val re = DoubleArray(n) { random.nextDouble() - 0.5 }
        val im = DoubleArray(n) { random.nextDouble() - 0.5 }
        // The real part of a direct DFT of the same input, before the transform overwrites it.
        val inputRe = re.copyOf()
        val inputIm = im.copyOf()
        AudioFingerprint.fft(re, im)
        val expected = DoubleArray(n) { k -> (0 until n).sumOf { t -> inputRe[t] * kotlin.math.cos(2 * PI * k * t / n) + inputIm[t] * kotlin.math.sin(2 * PI * k * t / n) } }
        for (k in listOf(0, 1, 17, 500, 1023, 2047)) assertEquals(expected[k], re[k], 1e-6)
    }
}
