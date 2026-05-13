@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package mongongo

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

public interface MongoCodec<T : Any> {
    public fun encode(value: T): BsonDocument

    public fun decode(document: BsonDocument): T
}

public class KotlinxBsonCodec<T : Any>(
    public val serializer: KSerializer<T>
) : MongoCodec<T> {
    override fun encode(value: T): BsonDocument {
        val encoded = encodeToBsonValue(serializer, value)
        return encoded as? BsonDocument
            ?: throw SerializationException(
                "kotlinx BSON codec requires ${serializer.descriptor.serialName} to encode as a BSON document"
            )
    }

    override fun decode(document: BsonDocument): T =
        decodeFromBsonValue(serializer, document)
}

public object BsonDocumentCodec : MongoCodec<BsonDocument> {
    override fun encode(value: BsonDocument): BsonDocument = value

    override fun decode(document: BsonDocument): BsonDocument = document
}

public typealias BsonCollection = MongoCollection<BsonDocument>

public typealias BsonCursor = MongoCursor<BsonDocument>

private const val BsonObjectIdSerialName = "mongongo.BsonObjectId"

private fun <T> encodeToBsonValue(serializer: SerializationStrategy<T>, value: T): BsonValue {
    var encoded: BsonValue? = null
    BsonValueEncoder { bsonValue ->
        check(encoded == null) { "Serializer for ${serializer.descriptor.serialName} encoded multiple BSON values" }
        encoded = bsonValue
    }
        .encodeSerializableValue(serializer, value)

    return encoded
        ?: throw SerializationException("Serializer for ${serializer.descriptor.serialName} did not encode a BSON value")
}

private fun <T> decodeFromBsonValue(deserializer: DeserializationStrategy<T>, value: BsonValue): T =
    BsonValueDecoder(value).decodeSerializableValue(deserializer)

private abstract class BsonEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    protected abstract fun put(value: BsonValue)

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        compositeEncoderFor(descriptor, ::put)

    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder =
        beginStructure(descriptor)

    override fun encodeValue(value: Any) {
        put(
            when (value) {
                is String -> BsonString(value)
                is Int -> BsonInt32(value)
                is Long -> BsonInt64(value)
                is Double -> BsonDouble(value)
                is Boolean -> BsonBoolean(value)
                else -> unsupported("value ${value::class.simpleName ?: value::class.toString()}")
            }
        )
    }

    override fun encodeNull() {
        put(BsonNull)
    }

    override fun encodeByte(value: Byte): Unit = unsupported("Byte")

    override fun encodeShort(value: Short): Unit = unsupported("Short")

    override fun encodeFloat(value: Float): Unit = unsupported("Float")

    override fun encodeChar(value: Char): Unit = unsupported("Char")

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int): Unit =
        unsupported("enum ${enumDescriptor.serialName}")

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        if (serializer.descriptor.isBsonObjectIdDescriptor()) {
            put(
                value as? BsonObjectId
                    ?: throw SerializationException("Expected BsonObjectId for ${serializer.descriptor.serialName}")
            )
            return
        }

        serializer.serialize(this, value)
    }
}

private class BsonValueEncoder(
    private val sink: (BsonValue) -> Unit
) : BsonEncoder() {
    override fun put(value: BsonValue) {
        sink(value)
    }
}

private abstract class BsonCompositeEncoder : BsonEncoder() {
    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        if (serializer.descriptor.isBsonObjectIdDescriptor()) {
            put(
                value as? BsonObjectId
                    ?: throw SerializationException("Expected BsonObjectId for ${serializer.descriptor.serialName}")
            )
            return
        }

        put(encodeToBsonValue(serializer, value))
    }
}

private class BsonDocumentEncoder(
    private val sink: (BsonValue) -> Unit
) : BsonCompositeEncoder() {
    private val values = linkedMapOf<String, BsonValue>()
    private var currentName: String? = null

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        currentName = descriptor.getElementName(index)
        return true
    }

    override fun put(value: BsonValue) {
        val name =
            currentName
                ?: throw SerializationException("BSON document encoder has no current field name")
        values[name] = value
        currentName = null
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        sink(BsonDocument(values))
    }
}

private class BsonArrayEncoder(
    private val sink: (BsonValue) -> Unit
) : BsonCompositeEncoder() {
    private val values = mutableListOf<BsonValue>()

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean = true

    override fun put(value: BsonValue) {
        values.add(value)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        sink(BsonArray(values))
    }
}

private fun compositeEncoderFor(descriptor: SerialDescriptor, sink: (BsonValue) -> Unit): CompositeEncoder =
    when (descriptor.kind) {
        StructureKind.CLASS,
        StructureKind.OBJECT -> BsonDocumentEncoder(sink)
        StructureKind.LIST -> BsonArrayEncoder(sink)
        StructureKind.MAP -> unsupported("Map ${descriptor.serialName}")
        else -> unsupported("${descriptor.serialName} with kind ${descriptor.kind}")
    }

private open class BsonValueDecoder(
    private val value: BsonValue
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    protected open fun currentValue(): BsonValue = value

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        unsupported("${descriptor.serialName} outside a BSON document or array")

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        compositeDecoderFor(descriptor, currentValue())

    override fun decodeNotNullMark(): Boolean = currentValue() !is BsonNull

    override fun decodeNull(): Nothing? {
        val value = currentValue()
        if (value !is BsonNull) {
            typeMismatch("BSON null", value)
        }
        return null
    }

    override fun decodeBoolean(): Boolean =
        (currentValue() as? BsonBoolean)?.value ?: typeMismatch("BSON bool", currentValue())

    override fun decodeByte(): Byte = unsupported("Byte")

    override fun decodeShort(): Short = unsupported("Short")

    override fun decodeInt(): Int =
        (currentValue() as? BsonInt32)?.value ?: typeMismatch("BSON int32", currentValue())

    override fun decodeLong(): Long =
        (currentValue() as? BsonInt64)?.value ?: typeMismatch("BSON int64", currentValue())

    override fun decodeFloat(): Float = unsupported("Float")

    override fun decodeDouble(): Double =
        (currentValue() as? BsonDouble)?.value ?: typeMismatch("BSON double", currentValue())

    override fun decodeChar(): Char = unsupported("Char")

    override fun decodeString(): String =
        (currentValue() as? BsonString)?.value ?: typeMismatch("BSON string", currentValue())

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = unsupported("enum ${enumDescriptor.serialName}")

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        if (deserializer.descriptor.isBsonObjectIdDescriptor()) {
            val objectId = currentValue() as? BsonObjectId ?: typeMismatch("BSON ObjectId", currentValue())
            @Suppress("UNCHECKED_CAST")
            return objectId as T
        }

        return deserializer.deserialize(this)
    }

    override fun <T> decodeSerializableValue(
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T = decodeSerializableValue(deserializer)
}

private class BsonDocumentDecoder(
    private val document: BsonDocument
) : BsonValueDecoder(document) {
    private val fieldNames = document.values.keys.toList()
    private var fieldCursor = 0
    private var currentFieldValue: BsonValue = BsonNull

    override fun currentValue(): BsonValue = currentFieldValue

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (fieldCursor < fieldNames.size) {
            val fieldName = fieldNames[fieldCursor++]
            val index = descriptor.getElementIndex(fieldName)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                continue
            }
            currentFieldValue = document.values.getValue(fieldName)
            return index
        }
        return CompositeDecoder.DECODE_DONE
    }
}

private class BsonArrayDecoder(
    private val array: BsonArray
) : BsonValueDecoder(array) {
    private var elementCursor = 0
    private var currentElementValue: BsonValue = BsonNull

    override fun currentValue(): BsonValue = currentElementValue

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (elementCursor >= array.values.size) {
            return CompositeDecoder.DECODE_DONE
        }
        currentElementValue = array.values[elementCursor]
        return elementCursor++
    }
}

private fun compositeDecoderFor(descriptor: SerialDescriptor, value: BsonValue): CompositeDecoder =
    when (descriptor.kind) {
        StructureKind.CLASS,
        StructureKind.OBJECT ->
            BsonDocumentDecoder(value as? BsonDocument ?: typeMismatch("BSON document", value))
        StructureKind.LIST ->
            BsonArrayDecoder(value as? BsonArray ?: typeMismatch("BSON array", value))
        StructureKind.MAP -> unsupported("Map ${descriptor.serialName}")
        else -> unsupported("${descriptor.serialName} with kind ${descriptor.kind}")
    }

private fun SerialDescriptor.isBsonObjectIdDescriptor(): Boolean =
    serialName == BsonObjectIdSerialName

private fun typeMismatch(expected: String, actual: BsonValue): Nothing =
    throw SerializationException("Expected $expected but found ${actual.bsonTypeName()}")

private fun unsupported(type: String): Nothing =
    throw SerializationException(
        "kotlinx BSON codec v0 does not support $type; supported mappings are String, Int, Long, Double, Boolean, null, nested @Serializable objects, List, and BsonObjectId"
    )

private fun BsonValue.bsonTypeName(): String =
    when (this) {
        is BsonDouble -> "BSON double"
        is BsonString -> "BSON string"
        is BsonDocument -> "BSON document"
        is BsonArray -> "BSON array"
        is BsonBinary -> "BSON binary"
        is BsonObjectId -> "BSON ObjectId"
        is BsonBoolean -> "BSON bool"
        is BsonDateTime -> "BSON datetime"
        BsonNull -> "BSON null"
        is BsonInt32 -> "BSON int32"
        is BsonTimestamp -> "BSON timestamp"
        is BsonInt64 -> "BSON int64"
    }
