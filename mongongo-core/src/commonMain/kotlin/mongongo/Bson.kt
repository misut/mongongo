package mongongo

public sealed interface BsonValue

public data class BsonDouble(val value: Double) : BsonValue

public data class BsonString(val value: String) : BsonValue

public data class BsonDocument(val values: Map<String, BsonValue>) : BsonValue {
    public constructor(vararg values: Pair<String, BsonValue>) : this(linkedMapOf(*values))

    public operator fun get(name: String): BsonValue? = values[name]
}

public data class BsonArray(val values: List<BsonValue>) : BsonValue

public data class BsonBinary(val subtype: Int, val bytes: List<Byte>) : BsonValue {
    init {
        require(subtype in 0..255) { "BSON binary subtype must fit in one byte" }
    }
}

public data class BsonObjectId(val bytes: List<Byte>) : BsonValue {
    init {
        require(bytes.size == ObjectIdByteCount) { "BSON ObjectId must contain 12 bytes" }
    }

    public companion object {
        private const val ObjectIdByteCount = 12

        public fun fromHex(hex: String): BsonObjectId {
            require(hex.length == ObjectIdByteCount * 2) { "BSON ObjectId hex must contain 24 characters" }

            return BsonObjectId(
                hex.chunked(2).map { byteHex -> byteHex.toInt(radix = 16).toByte() }
            )
        }
    }
}

public data class BsonBoolean(val value: Boolean) : BsonValue

public data class BsonDateTime(val epochMilliseconds: Long) : BsonValue

public data object BsonNull : BsonValue

public data class BsonInt32(val value: Int) : BsonValue

public data class BsonTimestamp(val increment: Int, val timestamp: Int) : BsonValue

public data class BsonInt64(val value: Long) : BsonValue

internal object BsonCodec {
    fun encode(document: BsonDocument): ByteArray {
        val writer = ByteWriter()
        writeDocument(writer, document)
        return writer.toByteArray()
    }

    fun decodeDocument(bytes: ByteArray): BsonDocument = ByteReader(bytes).readDocument()

    private fun writeDocument(writer: ByteWriter, document: BsonDocument) {
        val start = writer.size
        writer.writeInt32(0)

        for ((name, value) in document.values) {
            writer.writeByte(typeOf(value))
            writer.writeCString(name)
            writeValue(writer, value)
        }

        writer.writeByte(0)
        writer.setInt32(start, writer.size - start)
    }

    private fun typeOf(value: BsonValue): Int =
        when (value) {
            is BsonDouble -> 0x01
            is BsonString -> 0x02
            is BsonDocument -> 0x03
            is BsonArray -> 0x04
            is BsonBinary -> 0x05
            is BsonObjectId -> 0x07
            is BsonBoolean -> 0x08
            is BsonDateTime -> 0x09
            BsonNull -> 0x0a
            is BsonInt32 -> 0x10
            is BsonTimestamp -> 0x11
            is BsonInt64 -> 0x12
        }

    private fun writeValue(writer: ByteWriter, value: BsonValue) {
        when (value) {
            is BsonDouble -> writer.writeDouble(value.value)
            is BsonString -> writer.writeString(value.value)
            is BsonDocument -> writeDocument(writer, value)
            is BsonArray ->
                writeDocument(
                    writer,
                    BsonDocument(value.values.mapIndexed { index, item -> index.toString() to item }.toMap())
                )
            is BsonBinary -> {
                writer.writeInt32(value.bytes.size)
                writer.writeByte(value.subtype)
                writer.writeBytes(value.bytes.toByteArray())
            }
            is BsonObjectId -> writer.writeBytes(value.bytes.toByteArray())
            is BsonBoolean -> writer.writeByte(if (value.value) 1 else 0)
            is BsonDateTime -> writer.writeInt64(value.epochMilliseconds)
            BsonNull -> Unit
            is BsonInt32 -> writer.writeInt32(value.value)
            is BsonTimestamp -> {
                val packed =
                    (value.increment.toLong() and 0xffffffffL) or
                        ((value.timestamp.toLong() and 0xffffffffL) shl 32)
                writer.writeInt64(packed)
            }
            is BsonInt64 -> writer.writeInt64(value.value)
        }
    }
}

private class ByteWriter {
    private val bytes = mutableListOf<Byte>()

    val size: Int
        get() = bytes.size

    fun writeByte(value: Int) {
        bytes.add(value.toByte())
    }

    fun writeBytes(value: ByteArray) {
        for (byte in value) {
            bytes.add(byte)
        }
    }

    fun writeCString(value: String) {
        require('\u0000' !in value) { "BSON cstring cannot contain null bytes" }
        writeBytes(value.encodeToByteArray())
        writeByte(0)
    }

    fun writeString(value: String) {
        require('\u0000' !in value) { "BSON string cannot contain null bytes" }
        val bytes = value.encodeToByteArray()
        writeInt32(bytes.size + 1)
        writeBytes(bytes)
        writeByte(0)
    }

    fun writeInt32(value: Int) {
        repeat(4) { index -> writeByte(value ushr (index * 8)) }
    }

    fun writeInt64(value: Long) {
        repeat(8) { index -> writeByte((value ushr (index * 8)).toInt()) }
    }

    fun writeDouble(value: Double) {
        writeInt64(value.toRawBits())
    }

    fun setInt32(index: Int, value: Int) {
        repeat(4) { offset ->
            bytes[index + offset] = (value ushr (offset * 8)).toByte()
        }
    }

    fun toByteArray(): ByteArray = ByteArray(bytes.size) { index -> bytes[index] }
}

private class ByteReader(private val bytes: ByteArray) {
    private var position = 0

    fun readDocument(): BsonDocument {
        val start = position
        val length = readInt32()
        require(length >= 5) { "BSON document length must be at least 5 bytes" }

        val end = start + length
        require(end <= bytes.size) { "BSON document length exceeds input size" }

        val values = linkedMapOf<String, BsonValue>()
        while (position < end - 1) {
            val type = readByte()
            val name = readCString()
            values[name] = readValue(type)
        }

        require(readByte() == 0) { "BSON document must end with a null byte" }
        require(position == end) { "BSON document parser stopped at the wrong offset" }

        return BsonDocument(values)
    }

    private fun readValue(type: Int): BsonValue =
        when (type) {
            0x01 -> BsonDouble(readDouble())
            0x02 -> BsonString(readString())
            0x03 -> readDocument()
            0x04 -> readArray()
            0x05 -> {
                val length = readInt32()
                require(length >= 0) { "BSON binary length cannot be negative" }
                val subtype = readByte()
                BsonBinary(subtype, readBytes(length).toList())
            }
            0x07 -> BsonObjectId(readBytes(12).toList())
            0x08 -> BsonBoolean(readByte() != 0)
            0x09 -> BsonDateTime(readInt64())
            0x0a -> BsonNull
            0x10 -> BsonInt32(readInt32())
            0x11 -> {
                val packed = readInt64()
                BsonTimestamp(
                    increment = (packed and 0xffffffffL).toInt(),
                    timestamp = ((packed ushr 32) and 0xffffffffL).toInt()
                )
            }
            0x12 -> BsonInt64(readInt64())
            else -> throw UnsupportedOperationException("Unsupported BSON type 0x${type.toString(16)}")
        }

    private fun readArray(): BsonArray {
        val document = readDocument()
        val values = mutableListOf<BsonValue>()
        for (index in 0 until document.values.size) {
            values.add(document.values[index.toString()] ?: error("BSON array is missing index $index"))
        }
        return BsonArray(values)
    }

    private fun readByte(): Int {
        require(position < bytes.size) { "Unexpected end of BSON input" }
        return bytes[position++].toInt() and 0xff
    }

    private fun readBytes(length: Int): ByteArray {
        require(length >= 0) { "Byte length cannot be negative" }
        require(position + length <= bytes.size) { "Unexpected end of BSON input" }
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    private fun readCString(): String {
        val start = position
        while (position < bytes.size && bytes[position] != 0.toByte()) {
            position++
        }
        require(position < bytes.size) { "Unterminated BSON cstring" }
        val value = bytes.copyOfRange(start, position).decodeToString()
        position++
        return value
    }

    private fun readString(): String {
        val length = readInt32()
        require(length >= 1) { "BSON string length must include a trailing null byte" }
        val value = readBytes(length - 1).decodeToString()
        require(readByte() == 0) { "BSON string must end with a null byte" }
        return value
    }

    private fun readInt32(): Int {
        var result = 0
        repeat(4) { index -> result = result or (readByte() shl (index * 8)) }
        return result
    }

    private fun readInt64(): Long {
        var result = 0L
        repeat(8) { index -> result = result or (readByte().toLong() shl (index * 8)) }
        return result
    }

    private fun readDouble(): Double = Double.fromBits(readInt64())
}
