package fr.spotifeur.playback

import fr.spotifeur.data.Proto
import fr.spotifeur.data.proto
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer

/** Contracts from Android 9.2.0.26: Download.GetMediaHandleId and cv70.run. */
internal object NativeAudioProtocol {
    const val SERVICE = "spotify.download.esperanto.proto.Download"
    const val METHOD = "GetMediaHandleId"
    const val TIMEOUT_SECONDS = 30L

    fun keyId(bytes: ByteArray): String {
        require(bytes.size >= 16) { "A media key identifier must contain 16 bytes." }
        return bytes.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun handleRequest(fileId: String): ByteArray {
        require(fileId.length == 40 && fileId.all { it in '0'..'9' || it in 'a'..'f' })
        return proto { writeEnum(1, 1); writeString(2, fileId) } // MediaType.AUDIO=1
    }

    fun fileId(uri: String, key: ByteArray): String {
        val prefix = keyId(key)
        return Regex("[a-fA-F0-9]{40}").findAll(uri).map { it.value.lowercase() }
            .firstOrNull { it.startsWith(prefix) }
            ?: throw NativeAudioException("Missing full audio file ID.")
    }

    fun handleResponse(bytes: ByteArray): MediaHandleResult {
        val response = Proto(bytes)
        val status = response.number(2) ?: 0
        val handle = response.number(1) ?: 0
        return when (status) {
            0 -> if (handle != 0) MediaHandleResult.Ready(handle) else MediaHandleResult.Pending
            1 -> MediaHandleResult.Failed
            2 -> MediaHandleResult.Sunset
            else -> throw NativeAudioException("Unknown media handle status.", status)
        }
    }
}

internal sealed interface MediaHandleResult {
    class Ready(val handle: Int) : MediaHandleResult { override fun toString() = "Ready" }
    data object Pending : MediaHandleResult
    data object Failed : MediaHandleResult
    data object Sunset : MediaHandleResult
}

internal enum class NativeAudioScheme(val wireName: String, val mode: String, val resetPerSegment: Boolean, val partialFinalUnit: Boolean) {
    CENC("cenc", "STREAM", false, true), CENS("cens", "STREAM", false, true),
    CBC1("cbc1", "BLOCK", false, false), CBCS("cbcs", "BLOCK", true, false);

    companion object {
        fun fromWireName(value: String): NativeAudioScheme = entries.firstOrNull { it.wireName == value }
            ?: throw NativeAudioException("Unsupported native audio scheme.")
    }
}

internal open class NativeAudioException(message: String, val nativeCode: Int? = null, cause: Throwable? = null) : IOException(message, cause)

internal class NativeProcessorRefused(code: Int?) :
    NativeAudioException("The native engine refused to create a media processor.", code)

/** The processor refused the standard variant, so automatic quality fallback cannot help. */
internal class NativePlaybackRefused(cause: NativeProcessorRefused) :
    NativeAudioException("Spotify refused to prepare playback at standard quality.", cause.nativeCode, cause)

internal fun Throwable.hasNativePlaybackRefusal(): Boolean =
    generateSequence(this) { it.cause }.take(12).any { it is NativePlaybackRefused }

/** One instance per media session. The supplied engine owns its opaque processing state. */
internal class NativeSampleProcessor(private val factory: Any) : AutoCloseable {
    private val create = factory.javaClass.methods.single {
        it.name == "createProcessor" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType
    }
    private val modes = create.parameterTypes[1].enumConstants?.associateBy { (it as Enum<*>).name }
        ?: throw NativeAudioException("Unsupported processing mode type.")
    private data class Entry(val processor: Any, val process: Method, val destroy: Method)
    private val processors = mutableMapOf<Pair<Int, String>, Entry>()
    private var closed = false

    /** Runs synchronously on the codec thread, before queueInputBuffer; never on the UI thread. */
    @Synchronized
    fun processSample(handle: Int, scheme: NativeAudioScheme, buffer: ByteBuffer, offset: Int,
                      segmentCount: Int, clear: IntArray?, encrypted: IntArray,
                      processBlocks: Int, skipBlocks: Int, iv: ByteArray): Int {
        check(!closed) { "The media session has been released." }
        require(handle != 0 && buffer.isDirect && !buffer.isReadOnly)
        require(offset >= 0 && segmentCount > 0 && encrypted.size >= segmentCount)
        require(clear == null || clear.size >= segmentCount)
        require(processBlocks >= 0 && skipBlocks >= 0 && iv.size == 16)
        val skipped = clear ?: IntArray(segmentCount)
        var size = 0L
        repeat(segmentCount) { index ->
            require(skipped[index] >= 0 && encrypted[index] >= 0)
            size += skipped[index].toLong() + encrypted[index]
        }
        require(size <= Int.MAX_VALUE && offset.toLong() + size <= buffer.limit())
        val entry = processors.getOrPut(handle to scheme.mode) {
            val result = call(create, factory, handle, modes[scheme.mode] ?: error("Missing processing mode."))
                ?: throw NativeAudioException("The native processor factory returned no result.")
            val getter = result.javaClass.methods.firstOrNull { it.name == "getProcessor" && it.parameterCount == 0 }
            if (getter == null) {
                val getCode = result.javaClass.methods.firstOrNull { it.name == "getCode" && it.parameterCount == 0 }
                val code = getCode?.let { call(it, result) as? Int }
                throw NativeProcessorRefused(code)
            }
            val processor = call(getter, result) ?: throw NativeAudioException("Missing media processor.")
            try {
                Entry(processor, processor.javaClass.getMethod("processSample", ByteBuffer::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
                    IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    ByteArray::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                    processor.javaClass.getMethod("destroy"))
            } catch (failure: Exception) {
                processor.javaClass.getMethod("destroy").invoke(processor)
                throw failure
            }
        }
        val code = call(entry.process, entry.processor, buffer, offset, segmentCount, skipped, encrypted,
            processBlocks, skipBlocks, iv, scheme.resetPerSegment, scheme.partialFinalUnit) as Int
        if (code != 0) throw NativeAudioException("Native audio processing failed.", code)
        return size.toInt()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        processors.values.forEach { entry ->
            try { call(entry.destroy, entry.processor) }
            catch (error: Exception) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        }
        processors.clear()
        failure?.let { throw it }
    }

    private fun call(method: Method, target: Any, vararg args: Any): Any? = try { method.invoke(target, *args) }
    catch (failure: InvocationTargetException) { throw NativeAudioException("Native audio call failed.", cause = failure.cause) }
}
