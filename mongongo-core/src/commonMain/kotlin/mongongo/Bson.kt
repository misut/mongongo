package mongongo

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

public sealed interface BsonValue

public data class BsonDouble(val value: Double) : BsonValue

public data class BsonString(val value: String) : BsonValue

public data class BsonDocument(val values: Map<String, BsonValue>) : BsonValue {
    public constructor(vararg values: Pair<String, BsonValue>) : this(linkedMapOf(*values))

    public operator fun get(name: String): BsonValue? = values[name]

    public fun isEmpty(): Boolean = values.isEmpty()

    public operator fun contains(name: String): Boolean = values.containsKey(name)

    public fun getDocument(name: String): BsonDocument? = values[name] as? BsonDocument

    public fun getArray(name: String): BsonArray? = values[name] as? BsonArray

    public fun getString(name: String): String? = (values[name] as? BsonString)?.value

    public fun getBoolean(name: String): Boolean? = (values[name] as? BsonBoolean)?.value

    public fun getInt32(name: String): Int? = (values[name] as? BsonInt32)?.value

    public fun getInt64(name: String): Long? = (values[name] as? BsonInt64)?.value

    public fun getDouble(name: String): Double? = (values[name] as? BsonDouble)?.value

    public fun getNumberAsLong(name: String): Long? =
        when (val value = values[name]) {
            is BsonInt32 -> value.value.toLong()
            is BsonInt64 -> value.value
            else -> null
        }

    public fun plus(name: String, value: BsonValue): BsonDocument {
        val copy = linkedMapOf<String, BsonValue>()
        var replaced = false
        for ((existingName, existingValue) in values) {
            if (existingName == name) {
                copy[existingName] = value
                replaced = true
            } else {
                copy[existingName] = existingValue
            }
        }
        if (!replaced) {
            copy[name] = value
        }
        return BsonDocument(copy)
    }

    public fun without(name: String): BsonDocument {
        val copy = linkedMapOf<String, BsonValue>()
        for ((existingName, existingValue) in values) {
            if (existingName != name) {
                copy[existingName] = existingValue
            }
        }
        return BsonDocument(copy)
    }
}

internal fun BsonDocument.withValue(name: String, value: BsonValue): BsonDocument {
    val copy = linkedMapOf<String, BsonValue>()
    copy[name] = value
    for ((existingName, existingValue) in values) {
        if (existingName != name) {
            copy[existingName] = existingValue
        }
    }
    return BsonDocument(copy)
}

public data class BsonArray(val values: List<BsonValue>) : BsonValue

public data class BsonBinary(val subtype: Int, val bytes: List<Byte>) : BsonValue {
    init {
        require(subtype in 0..255) { "BSON binary subtype must fit in one byte" }
    }
}

@Serializable(with = BsonObjectIdSerializer::class)
public data class BsonObjectId(val bytes: List<Byte>) : BsonValue {
    init {
        require(bytes.size == ObjectIdByteCount) { "BSON ObjectId must contain 12 bytes" }
    }

    public fun toHex(): String =
        bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }

    public companion object {
        private const val ObjectIdByteCount = 12

        public fun fromHex(hex: String): BsonObjectId {
            require(hex.length == ObjectIdByteCount * 2) { "BSON ObjectId hex must contain 24 characters" }

            return BsonObjectId(
                hex.chunked(2).map { byteHex -> byteHex.toInt(radix = 16).toByte() }
            )
        }

        internal fun generate(): BsonObjectId = BsonObjectIdGenerator.generate()
    }
}

public object BsonObjectIdSerializer : KSerializer<BsonObjectId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("mongongo.BsonObjectId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BsonObjectId) {
        encoder.encodeString(value.toHex())
    }

    override fun deserialize(decoder: Decoder): BsonObjectId =
        BsonObjectId.fromHex(decoder.decodeString())
}

@OptIn(ExperimentalAtomicApi::class)
private object BsonObjectIdGenerator {
    private const val TimestampByteCount = 4
    private const val ProcessUniqueByteCount = 5
    private const val CounterByteCount = 3
    private const val CounterMask = 0x00ff_ffff

    private val processUnique = List(ProcessUniqueByteCount) { Random.nextInt(0, 256).toByte() }
    private val counter = AtomicInt(Random.nextInt())

    fun generate(): BsonObjectId {
        val timestampSeconds = Clock.System.now().epochSeconds
        val sequence = counter.addAndFetch(1) and CounterMask
        val bytes = mutableListOf<Byte>()

        repeat(TimestampByteCount) { index ->
            bytes.add((timestampSeconds ushr ((TimestampByteCount - 1 - index) * 8)).toByte())
        }
        bytes.addAll(processUnique)
        repeat(CounterByteCount) { index ->
            bytes.add((sequence ushr ((CounterByteCount - 1 - index) * 8)).toByte())
        }

        return BsonObjectId(bytes)
    }
}

public data class BsonBoolean(val value: Boolean) : BsonValue

public data class BsonDateTime(val epochMilliseconds: Long) : BsonValue

public data object BsonNull : BsonValue

public data class BsonInt32(val value: Int) : BsonValue

public data class BsonTimestamp(val increment: Int, val timestamp: Int) : BsonValue

public data class BsonInt64(val value: Long) : BsonValue

public fun bsonDocument(block: BsonDocumentBuilder.() -> Unit): BsonDocument =
    BsonDocumentBuilder().apply(block).build()

public class BsonDocumentBuilder {
    private val values = linkedMapOf<String, BsonValue>()

    public fun build(): BsonDocument = BsonDocument(values.toMap())

    public fun value(name: String, value: BsonValue) {
        values[name] = value
    }

    public fun double(name: String, value: Double) {
        this.value(name, BsonDouble(value))
    }

    public fun string(name: String, value: String) {
        this.value(name, BsonString(value))
    }

    public fun document(name: String, value: BsonDocument) {
        this.value(name, value)
    }

    public fun document(name: String, block: BsonDocumentBuilder.() -> Unit) {
        document(name, bsonDocument(block))
    }

    public fun array(name: String, values: List<BsonValue>) {
        this.value(name, BsonArray(values))
    }

    public fun array(name: String, vararg values: BsonValue) {
        array(name, values.toList())
    }

    public fun binary(name: String, subtype: Int, bytes: List<Byte>) {
        this.value(name, BsonBinary(subtype = subtype, bytes = bytes))
    }

    public fun binary(name: String, subtype: Int, bytes: ByteArray) {
        binary(name, subtype, bytes.toList())
    }

    public fun objectId(name: String, value: BsonObjectId) {
        this.value(name, value)
    }

    public fun objectId(name: String, hex: String) {
        objectId(name, BsonObjectId.fromHex(hex))
    }

    public fun boolean(name: String, value: Boolean) {
        this.value(name, BsonBoolean(value))
    }

    public fun dateTime(name: String, epochMilliseconds: Long) {
        this.value(name, BsonDateTime(epochMilliseconds))
    }

    public fun nullValue(name: String) {
        this.value(name, BsonNull)
    }

    public fun int32(name: String, value: Int) {
        this.value(name, BsonInt32(value))
    }

    public fun timestamp(name: String, increment: Int, timestamp: Int) {
        this.value(name, BsonTimestamp(increment = increment, timestamp = timestamp))
    }

    public fun int64(name: String, value: Long) {
        this.value(name, BsonInt64(value))
    }
}

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
                    BsonDocument(
                        value.values
                            .mapIndexed { index, item -> index.toString() to item }
                            .toMap(linkedMapOf())
                    )
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

    fun readDocument(): BsonDocument = readDocument(maxEnd = bytes.size)

    private fun readDocument(maxEnd: Int): BsonDocument {
        val start = position
        val length = readInt32(limit = maxEnd)
        require(length >= 5) { "BSON document length must be at least 5 bytes" }

        require(length <= bytes.size - start) { "BSON document length exceeds input size" }
        val end = start + length
        require(end <= maxEnd) { "BSON document length exceeds containing document" }

        val values = linkedMapOf<String, BsonValue>()
        while (position < end - 1) {
            val type = readByte(limit = end)
            val name = readCString(limit = end)
            values[name] = readValue(type, end)
        }

        require(readByte(limit = end) == 0) { "BSON document must end with a null byte" }
        require(position == end) { "BSON document parser stopped at the wrong offset" }

        return BsonDocument(values)
    }

    private fun readValue(type: Int, limit: Int): BsonValue =
        when (type) {
            0x01 -> BsonDouble(readDouble(limit))
            0x02 -> BsonString(readString(limit))
            0x03 -> readDocument(maxEnd = limit)
            0x04 -> readArray(limit)
            0x05 -> {
                val length = readInt32(limit)
                require(length >= 0) { "BSON binary length cannot be negative" }
                val subtype = readByte(limit)
                BsonBinary(subtype, readBytes(length, limit).toList())
            }
            0x07 -> BsonObjectId(readBytes(12, limit).toList())
            0x08 -> BsonBoolean(readByte(limit) != 0)
            0x09 -> BsonDateTime(readInt64(limit))
            0x0a -> BsonNull
            0x10 -> BsonInt32(readInt32(limit))
            0x11 -> {
                val packed = readInt64(limit)
                BsonTimestamp(
                    increment = (packed and 0xffffffffL).toInt(),
                    timestamp = ((packed ushr 32) and 0xffffffffL).toInt()
                )
            }
            0x12 -> BsonInt64(readInt64(limit))
            else -> throw IllegalArgumentException("Unsupported BSON type 0x${type.toString(16)}")
        }

    private fun readArray(limit: Int): BsonArray {
        val document = readDocument(maxEnd = limit)
        val values = mutableListOf<BsonValue>()
        for ((expectedIndex, entry) in document.values.entries.withIndex()) {
            val expectedName = expectedIndex.toString()
            val actualIndex = entry.key.toIntOrNull()
            require(actualIndex != null && actualIndex >= 0 && actualIndex.toString() == entry.key) {
                "BSON array index ${entry.key} is invalid"
            }
            require(entry.key == expectedName) {
                if (expectedName in document.values) {
                    "BSON array index ${entry.key} is out of order, expected $expectedName"
                } else {
                    "BSON array is missing index $expectedName"
                }
            }
            values.add(entry.value)
        }
        return BsonArray(values)
    }

    private fun readByte(limit: Int = bytes.size): Int {
        require(position < limit && position < bytes.size) { "Unexpected end of BSON input" }
        return bytes[position++].toInt() and 0xff
    }

    private fun readBytes(length: Int, limit: Int = bytes.size): ByteArray {
        require(length >= 0) { "Byte length cannot be negative" }
        require(length <= bytes.size - position && length <= limit - position) { "Unexpected end of BSON input" }
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    private fun readCString(limit: Int): String {
        val start = position
        while (position < limit && position < bytes.size && bytes[position] != 0.toByte()) {
            position++
        }
        require(position < limit && position < bytes.size) { "Unterminated BSON cstring" }
        val value = bytes.copyOfRange(start, position).decodeToString()
        position++
        return value
    }

    private fun readString(limit: Int): String {
        val length = readInt32(limit)
        require(length >= 1) { "BSON string length must include a trailing null byte" }
        require(length <= bytes.size - position && length <= limit - position) { "Unterminated BSON string" }
        val value = readBytes(length - 1, limit).decodeToString()
        require(readByte(limit) == 0) { "Unterminated BSON string" }
        return value
    }

    private fun readInt32(limit: Int = bytes.size): Int {
        var result = 0
        repeat(4) { index -> result = result or (readByte(limit) shl (index * 8)) }
        return result
    }

    private fun readInt64(limit: Int = bytes.size): Long {
        var result = 0L
        repeat(8) { index -> result = result or (readByte(limit).toLong() shl (index * 8)) }
        return result
    }

    private fun readDouble(limit: Int): Double = Double.fromBits(readInt64(limit))
}
