package fr.spotifeur.data

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import java.io.ByteArrayOutputStream

internal fun proto(write: CodedOutputStream.() -> Unit): ByteArray {
    val buffer = ByteArrayOutputStream()
    CodedOutputStream.newInstance(buffer).apply {
        write()
        flush()
    }
    return buffer.toByteArray()
}

internal class Proto(bytes: ByteArray) {
    private val data = mutableMapOf<Int, MutableList<ByteArray>>()
    private val numbers = mutableMapOf<Int, Int>()
    private val longs = mutableMapOf<Int, Long>()
    private val fixed32 = mutableMapOf<Int, Int>()

    init {
        val input = CodedInputStream.newInstance(bytes)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            val field = WireFormat.getTagFieldNumber(tag)
            when (WireFormat.getTagWireType(tag)) {
                WireFormat.WIRETYPE_LENGTH_DELIMITED ->
                    data.getOrPut(field, ::mutableListOf).add(input.readByteArray())
                WireFormat.WIRETYPE_VARINT -> input.readInt64().let { longs[field] = it; numbers[field] = it.toInt() }
                WireFormat.WIRETYPE_FIXED32 -> fixed32[field] = input.readFixed32()
                else -> if (!input.skipField(tag)) error("Invalid Protobuf response.")
            }
        }
    }

    fun bytes(field: Int): ByteArray? = data[field]?.lastOrNull()
    fun text(field: Int): String? = bytes(field)?.decodeToString()
    fun texts(field: Int): List<String> = data[field].orEmpty().map { it.decodeToString() }
    fun number(field: Int): Int? = numbers[field]
    /** A 64-bit varint, such as a play count. */
    fun long(field: Int): Long? = longs[field]
    /** A `float` field (wire type 5). */
    fun float(field: Int): Float? = fixed32[field]?.let(java.lang.Float::intBitsToFloat)
    fun message(field: Int): Proto? = bytes(field)?.let(::Proto)
    fun messages(field: Int): List<Proto> = data[field].orEmpty().map(::Proto)
}
