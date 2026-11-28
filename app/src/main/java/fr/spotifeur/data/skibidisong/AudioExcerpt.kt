package fr.spotifeur.data.skibidisong

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded mono audio from the start of a file. */
internal class DecodedAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate == 0) 0 else samples.size * 1000L / sampleRate
}

/**
 * Decodes the first seconds of a partial audio file (the first bytes of an MP4 or WebM stream).
 * Encrypted samples are never decoded: reading stops at the first one, so a Spotify file gives its
 * clear lead-in.
 */
@UnstableApi
internal object AudioExcerpt {
    private class Sample(val data: ByteArray, val timeUs: Long)

    fun decode(bytes: ByteArray, maxDurationMs: Long): DecodedAudio? {
        val t0 = System.nanoTime()
        val (format, samples) = demux(bytes, maxDurationMs * 1000) ?: return null
        if (samples.isEmpty()) return null
        val t1 = System.nanoTime()
        return pcm(format, samples, maxDurationMs).also {
            android.util.Log.d("Skibidisong", "decode ${format.sampleMimeType} ${format.codecs}: demux ${(t1 - t0) / 1_000_000} ms " +
                "(${samples.size} samples), codec ${(System.nanoTime() - t1) / 1_000_000} ms")
        }
    }

    /** The audio track's format and its clear samples, in order. */
    private fun demux(bytes: ByteArray, maxUs: Long): Pair<Format, List<Sample>>? {
        val candidates = listOf<() -> Extractor>(
            { FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0) },
            { Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED) },
            { MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES) },
        )
        val extractor = candidates.map { it() }.firstOrNull { candidate ->
            runCatching { candidate.sniff(input(bytes, 0)) }.getOrDefault(false)
        } ?: return null
        val output = Collector(maxUs)
        extractor.init(output)
        var input = input(bytes, 0)
        val position = PositionHolder()
        try {
            while (!output.done) {
                when (extractor.read(input, position)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> {
                        if (position.position >= bytes.size) break
                        input = input(bytes, position.position)
                    }
                }
            }
        } catch (_: EOFException) { /* The excerpt ends in the middle of a box or a block. */ }
        catch (_: androidx.media3.common.ParserException) { }
        catch (_: IllegalStateException) { }
        finally { runCatching { extractor.release() } }
        val format = output.format ?: return null
        return format to output.samples
    }

    private fun input(bytes: ByteArray, from: Long): DefaultExtractorInput {
        var position = from.toInt()
        val reader = DataReader { target, offset, length ->
            if (position >= bytes.size) C.RESULT_END_OF_INPUT
            else {
                val count = minOf(length, bytes.size - position)
                System.arraycopy(bytes, position, target, offset, count)
                position += count
                count
            }
        }
        return DefaultExtractorInput(reader, from, bytes.size.toLong())
    }

    /** Keeps the first audio track, until the first encrypted sample or [maxUs]. */
    private class Collector(private val maxUs: Long) : ExtractorOutput {
        var format: Format? = null
        val samples = ArrayList<Sample>()
        var done = false
        private var audioId: Int? = null
        private var pending = ByteArray(64 * 1024)
        private var pendingSize = 0

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_AUDIO || (audioId != null && audioId != id)) return androidx.media3.extractor.DiscardingTrackOutput()
            audioId = id
            return object : TrackOutput {
                override fun format(format: Format) { this@Collector.format = format }
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    ensure(length)
                    val read = input.read(pending, pendingSize, length)
                    if (read == C.RESULT_END_OF_INPUT) {
                        if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                        throw EOFException()
                    }
                    pendingSize += read
                    return read
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    ensure(length)
                    data.readBytes(pending, pendingSize, length)
                    pendingSize += length
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    val end = pendingSize - offset
                    val start = end - size
                    if (start < 0) { done = true; return }
                    if (flags and C.BUFFER_FLAG_ENCRYPTED != 0 || timeUs > maxUs) { done = true; return }
                    samples += Sample(pending.copyOfRange(start, end), timeUs)
                    System.arraycopy(pending, end, pending, 0, offset)
                    pendingSize = offset
                }
            }
        }

        private fun ensure(extra: Int) {
            if (pendingSize + extra > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + extra))
        }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    /**
     * The software AAC decoder runs in the media process and returns about one frame per
     * millisecond, so the excerpt is cut in parts decoded side by side. Each part after the first
     * starts a few frames early to rebuild the overlap, and every buffer goes back to its place by
     * timestamp, which keeps the parts aligned whatever the decoder's delay.
     */
    private fun pcm(format: Format, samples: List<Sample>, maxDurationMs: Long): DecodedAudio? {
        val mime = format.sampleMimeType ?: return null
        if (!MimeTypes.isAudio(mime)) return null
        val parts = (samples.size / MIN_PART_SAMPLES).coerceIn(1, PARTS)
        val bounds = IntArray(parts + 1) { it * samples.size / parts }
        val decoded = (0 until parts).map { part ->
            decoders.submit<DecodedPart?> {
                decodePart(format, samples, bounds[part], bounds[part + 1], if (part == 0) 0 else WARM_UP_SAMPLES)
            }
        }.map { runCatching { it.get() }.getOrNull() ?: return null }
        val rate = decoded.first().sampleRate
        if (rate <= 0 || decoded.any { it.sampleRate != rate }) return null
        val origin = samples.first().timeUs
        val length = minOf(decoded.maxOf { part -> part.buffers.maxOfOrNull { (time, data) -> position(time, origin, rate) + data.size } ?: 0 },
            (rate * maxDurationMs / 1000).toInt())
        if (length <= 0) return null
        val pcm = FloatArray(length)
        for (part in decoded) for ((time, data) in part.buffers) {
            val at = position(time, origin, rate)
            if (at >= length) continue
            System.arraycopy(data, 0, pcm, at.coerceAtLeast(0), minOf(data.size, length - at.coerceAtLeast(0)))
        }
        return DecodedAudio(pcm, rate)
    }

    private fun position(timeUs: Long, originUs: Long, rate: Int): Int = ((timeUs - originUs) * rate / 1_000_000).toInt()

    private class DecodedPart(val sampleRate: Int, val buffers: List<Pair<Long, FloatArray>>)

    /** Decodes samples [from, to) after [warmUp] earlier ones, whose output is dropped. */
    private fun decodePart(format: Format, samples: List<Sample>, from: Int, to: Int, warmUp: Int): DecodedPart? {
        val start = (from - warmUp).coerceAtLeast(0)
        val keepFromUs = samples[from].timeUs
        val mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(format.buildUpon().setDrmInitData(null).build())
        val codec = MediaCodec.createDecoderByType(checkNotNull(format.sampleMimeType))
        val buffers = ArrayList<Pair<Long, FloatArray>>()
        try {
            codec.configure(mediaFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var next = start
            var inputDone = false
            var outputDone = false
            var sampleRate = format.sampleRate
            var channels = format.channelCount
            var float = false
            val deadline = System.nanoTime() + DECODE_TIMEOUT_NS
            while (!outputDone && System.nanoTime() < deadline) {
                // Fill every free input slot, then wait for the next output: the codec works meanwhile.
                while (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index < 0) break
                    val buffer = checkNotNull(codec.getInputBuffer(index))
                    if (next < to) {
                        val sample = samples[next++]
                        buffer.clear(); buffer.put(sample.data)
                        codec.queueInputBuffer(index, 0, sample.data.size, sample.timeUs, 0)
                    } else {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
                val index = codec.dequeueOutputBuffer(info, OUTPUT_WAIT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    index >= 0 -> {
                        if (info.size > 0 && info.presentationTimeUs >= keepFromUs) {
                            val buffer = checkNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            buffers += info.presentationTimeUs to mono(buffer.order(ByteOrder.nativeOrder()), channels.coerceAtLeast(1), float)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return DecodedPart(sampleRate, buffers)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun mono(buffer: ByteBuffer, channels: Int, float: Boolean): FloatArray {
        val frames = buffer.remaining() / (channels * if (float) 4 else 2)
        return FloatArray(frames) {
            var sum = 0f
            repeat(channels) { sum += if (float) buffer.float else buffer.short / 32768f }
            sum / channels
        }
    }

    private val decoders = java.util.concurrent.Executors.newCachedThreadPool { task ->
        Thread(task, "skibidisong-decode").apply { isDaemon = true }
    }
    /** Parts decoded side by side, and the shortest part worth a decoder of its own. */
    private const val PARTS = 3
    private const val MIN_PART_SAMPLES = 60
    /** Earlier samples decoded before a part and dropped: AAC frames overlap their neighbours. */
    private const val WARM_UP_SAMPLES = 3
    private const val OUTPUT_WAIT_US = 2_000L
    private const val DECODE_TIMEOUT_NS = 8_000_000_000L
}
