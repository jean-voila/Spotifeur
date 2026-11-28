package fr.spotifeur.playback

import fr.spotifeur.data.proto
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class NativeAudioTest {
    @Test fun mediaHandleWireContract() {
        val kid = "00112233445566778899aabbccddeeff"
        val file = kid + "01234567"
        assertArrayEquals(byteArrayOf(8, 1, 18, 40) + file.encodeToByteArray(), NativeAudioProtocol.handleRequest(file))
        assertThrows(IllegalArgumentException::class.java) { NativeAudioProtocol.handleRequest(kid) }
        assertEquals(file, NativeAudioProtocol.fileId("https://example.test/audio/$file/file.mp4", ByteArray(16) { (it * 17).toByte() }))
        assertEquals(kid, NativeAudioProtocol.keyId(ByteArray(16) { (it * 17).toByte() } + byteArrayOf(99)))
        assertEquals(-1, (NativeAudioProtocol.handleResponse(proto { writeUInt32(1, -1) }) as MediaHandleResult.Ready).handle)
        assertSame(MediaHandleResult.Pending, NativeAudioProtocol.handleResponse(byteArrayOf()))
        assertSame(MediaHandleResult.Failed, NativeAudioProtocol.handleResponse(proto { writeEnum(2, 1) }))
        assertSame(MediaHandleResult.Sunset, NativeAudioProtocol.handleResponse(proto { writeEnum(2, 2) }))
    }

    @Test fun forwardsSchemeGeometryAndDestroysEachProcessorOnce() {
        val factory = Factory()
        val adapter = NativeSampleProcessor(factory)
        val buffer = ByteBuffer.allocateDirect(32)
        val iv = ByteArray(16) { it.toByte() }
        for (scheme in NativeAudioScheme.entries) {
            assertEquals(20, adapter.processSample(7, scheme, buffer, 3, 2, intArrayOf(2, 3), intArrayOf(7, 8), 1, 9, iv))
            val call = factory.last!!.last!!
            assertSame(buffer, call.buffer)
            assertEquals(3, call.offset)
            assertEquals(2, call.count)
            assertArrayEquals(iv, call.iv)
            assertEquals(1, call.blocks)
            assertEquals(9, call.skip)
            assertEquals(scheme.resetPerSegment, call.reset)
            assertEquals(scheme.partialFinalUnit, call.partial)
        }
        assertEquals(2, factory.created.size) // One STREAM and one BLOCK for this handle/session.
        assertEquals(listOf(Mode.STREAM, Mode.BLOCK), factory.modes)
        adapter.close(); adapter.close()
        assertTrue(factory.created.all { it.destroys == 1 })
        assertThrows(IllegalStateException::class.java) {
            adapter.processSample(7, NativeAudioScheme.CENC, buffer, 0, 1, null, intArrayOf(1), 0, 0, iv)
        }
    }

    @Test fun rejectsInvalidBoundsAndSurfacesNativeFailure() {
        val factory = Factory()
        val adapter = NativeSampleProcessor(factory)
        val iv = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            adapter.processSample(1, NativeAudioScheme.CENC, ByteBuffer.allocate(16), 0, 1, null, intArrayOf(16), 0, 0, iv)
        }
        assertThrows(IllegalArgumentException::class.java) {
            adapter.processSample(1, NativeAudioScheme.CENC, ByteBuffer.allocateDirect(16), 2, 1, null, intArrayOf(16), 0, 0, iv)
        }
        factory.code = 524
        val error = assertThrows(NativeAudioException::class.java) {
            adapter.processSample(1, NativeAudioScheme.CBCS, ByteBuffer.allocateDirect(16), 0, 1, null, intArrayOf(16), 1, 9, iv)
        }
        assertEquals(524, error.nativeCode)
        adapter.close()
    }

    @Test fun reportsProcessorRefusalWithoutLosingTheNativeCode() {
        NativeSampleProcessor(RejectingFactory()).use { adapter ->
            val refusal = assertThrows(NativeProcessorRefused::class.java) {
                adapter.processSample(1, NativeAudioScheme.CBCS, ByteBuffer.allocateDirect(16),
                    0, 1, null, intArrayOf(16), 1, 9, ByteArray(16))
            }
            assertEquals(5, refusal.nativeCode)
            assertFalse(refusal.hasNativePlaybackRefusal())
            val terminal = NativePlaybackRefused(refusal)
            assertEquals(5, terminal.nativeCode)
            assertTrue(IllegalStateException(terminal).hasNativePlaybackRefusal())
        }
    }

    enum class Mode { STREAM, BLOCK }
    class Result(private val value: Processor) { fun getProcessor() = value }
    class Refusal { fun getCode() = 5 }
    class RejectingFactory {
        @Suppress("UNUSED_PARAMETER")
        fun createProcessor(handle: Int, mode: Mode) = Refusal()
    }
    class Factory {
        val created = mutableListOf<Processor>()
        var last: Processor? = null
        val modes = mutableListOf<Mode>()
        var code = 0
        fun createProcessor(handle: Int, mode: Mode): Result {
            modes += mode
            assertEquals(7.takeIf { code == 0 } ?: 1, handle)
            return Result(Processor(code).also { created += it; last = it })
        }
    }
    data class Call(val buffer: ByteBuffer, val offset: Int, val count: Int, val blocks: Int, val skip: Int, val iv: ByteArray, val reset: Boolean, val partial: Boolean)
    class Processor(private val code: Int) {
        var last: Call? = null
        var destroys = 0
        fun processSample(buffer: ByteBuffer, offset: Int, count: Int, clear: IntArray, encrypted: IntArray, blocks: Int, skip: Int, iv: ByteArray, reset: Boolean, partial: Boolean): Int {
            assertEquals(count, clear.size); assertEquals(count, encrypted.size)
            last = Call(buffer, offset, count, blocks, skip, iv, reset, partial)
            return code
        }
        fun destroy() { destroys++ }
    }
}
