package com.thelightphone.tool.tesla

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format encoder.
 * Supports the subset needed for Tesla VCP messages.
 *
 * Wire types:
 *   0 = varint
 *   1 = 64-bit (fixed64, double)
 *   2 = length-delimited (bytes, string, embedded messages)
 *   5 = 32-bit (fixed32, float)
 */
class ProtobufEncoder {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    /** Write a varint field (uint32, uint64, int32, int64, enum, bool). */
    fun writeVarint(fieldNumber: Int, value: Long) {
        if (value == 0L) return // protobuf omits default values
        writeTag(fieldNumber, 0)
        writeRawVarint(value)
    }

    fun writeVarint(fieldNumber: Int, value: Int) =
        writeVarint(fieldNumber, value.toLong())

    fun writeBool(fieldNumber: Int, value: Boolean) =
        writeVarint(fieldNumber, if (value) 1L else 0L)

    /** Write an enum field. Always writes, even if value == 0, when [force] is true. */
    fun writeEnum(fieldNumber: Int, value: Int, force: Boolean = false) {
        if (value == 0 && !force) return
        writeTag(fieldNumber, 0)
        writeRawVarint(value.toLong())
    }

    /** Write a length-delimited field (bytes). */
    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        if (value.isEmpty()) return
        writeTag(fieldNumber, 2)
        writeRawVarint(value.size.toLong())
        out.write(value)
    }

    /** Write an embedded message field. */
    fun writeMessage(fieldNumber: Int, builder: ProtobufEncoder.() -> Unit) {
        val inner = ProtobufEncoder().apply(builder)
        val bytes = inner.toByteArray()
        if (bytes.isEmpty()) return
        writeTag(fieldNumber, 2)
        writeRawVarint(bytes.size.toLong())
        out.write(bytes)
    }

    /** Write a fixed32 field (e.g. for expires_at). */
    fun writeFixed32(fieldNumber: Int, value: Int) {
        if (value == 0) return
        writeTag(fieldNumber, 5)
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    /** Write a uint32 field — same as writeVarint but more readable at call sites. */
    fun writeUint32(fieldNumber: Int, value: Int) =
        writeVarint(fieldNumber, value)

    /** Write raw bytes without a field tag (for building sub-components). */
    fun writeRaw(bytes: ByteArray) {
        out.write(bytes)
    }

    /** Force-write a varint field even when value is 0 (needed for enums like RKE_ACTION_UNLOCK = 0). */
    fun writeVarintForce(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, 0)
        writeRawVarint(value.toLong())
    }

    /** Write a zero-length message field (protobuf Void / empty message for oneof selection). */
    fun writeEmptyMessage(fieldNumber: Int) {
        writeTag(fieldNumber, 2)
        writeRawVarint(0L)
    }

    // ── Internal ──────────────────────────────────────────

    private fun writeTag(fieldNumber: Int, wireType: Int) {
        writeRawVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeRawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/**
 * Minimal protobuf wire-format decoder.
 * Reads fields from a byte array.
 */
class ProtobufDecoder(private val data: ByteArray) {
    private var pos = 0

    data class Field(val fieldNumber: Int, val wireType: Int, val value: Any)

    fun hasRemaining(): Boolean = pos < data.size

    fun readField(): Field {
        val tag = readRawVarint().toInt()
        val fieldNumber = tag ushr 3
        val wireType = tag and 0x07
        val value: Any = when (wireType) {
            0 -> readRawVarint()                    // varint
            1 -> readFixed64()                       // 64-bit
            2 -> readLengthDelimited()               // bytes
            5 -> readFixed32()                       // 32-bit
            else -> throw IllegalArgumentException("Unknown wire type $wireType")
        }
        return Field(fieldNumber, wireType, value)
    }

    fun readAllFields(): Map<Int, Any> {
        val fields = mutableMapOf<Int, Any>()
        while (hasRemaining()) {
            val field = readField()
            fields[field.fieldNumber] = field.value
        }
        return fields
    }

    private fun readRawVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun readFixed32(): Int {
        val b0 = data[pos++].toInt() and 0xFF
        val b1 = data[pos++].toInt() and 0xFF
        val b2 = data[pos++].toInt() and 0xFF
        val b3 = data[pos++].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readFixed64(): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((data[pos++].toLong() and 0xFF) shl (i * 8))
        }
        return result
    }

    private fun readLengthDelimited(): ByteArray {
        val length = readRawVarint().toInt()
        val bytes = data.copyOfRange(pos, pos + length)
        pos += length
        return bytes
    }
}

/** Convenience: build a protobuf message and return the byte array. */
fun protobuf(builder: ProtobufEncoder.() -> Unit): ByteArray =
    ProtobufEncoder().apply(builder).toByteArray()
