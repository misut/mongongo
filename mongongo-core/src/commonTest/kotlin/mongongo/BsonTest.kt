package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BsonTest {
    @Test
    fun roundTripsSupportedBsonTypes() {
        val document =
            BsonDocument(
                "double" to BsonDouble(1.25),
                "string" to BsonString("mongongo"),
                "document" to BsonDocument("nested" to BsonInt32(7)),
                "array" to BsonArray(listOf(BsonString("first"), BsonInt64(2))),
                "binary" to BsonBinary(subtype = 0, bytes = listOf(1, 2, 3).map { it.toByte() }),
                "objectId" to BsonObjectId.fromHex("00112233445566778899aabb"),
                "boolean" to BsonBoolean(true),
                "dateTime" to BsonDateTime(1_700_000_000_000),
                "null" to BsonNull,
                "int32" to BsonInt32(42),
                "timestamp" to BsonTimestamp(increment = 5, timestamp = 9),
                "int64" to BsonInt64(9_000_000_000)
            )

        val decoded = BsonCodec.decodeDocument(BsonCodec.encode(document))

        assertEquals(document, decoded)
        assertEquals(document.values.keys.toList(), decoded.values.keys.toList())
    }

    @Test
    fun buildsDocumentsWithDsl() {
        val expected =
            BsonDocument(
                "double" to BsonDouble(1.25),
                "string" to BsonString("mongongo"),
                "document" to BsonDocument("nested" to BsonInt32(7)),
                "array" to BsonArray(listOf(BsonString("first"), BsonInt64(2))),
                "binary" to BsonBinary(subtype = 0, bytes = listOf(1, 2, 3).map { it.toByte() }),
                "objectId" to BsonObjectId.fromHex("00112233445566778899aabb"),
                "boolean" to BsonBoolean(true),
                "dateTime" to BsonDateTime(1_700_000_000_000),
                "null" to BsonNull,
                "int32" to BsonInt32(42),
                "timestamp" to BsonTimestamp(increment = 5, timestamp = 9),
                "int64" to BsonInt64(9_000_000_000)
            )

        val actual =
            bsonDocument {
                double("double", 1.25)
                string("string", "mongongo")
                document("document") {
                    int32("nested", 7)
                }
                array("array", BsonString("first"), BsonInt64(2))
                binary("binary", subtype = 0, bytes = byteArrayOf(1, 2, 3))
                objectId("objectId", "00112233445566778899aabb")
                boolean("boolean", true)
                dateTime("dateTime", 1_700_000_000_000)
                nullValue("null")
                int32("int32", 42)
                timestamp("timestamp", increment = 5, timestamp = 9)
                int64("int64", 9_000_000_000)
            }

        assertEquals(expected, actual)
        assertEquals(expected.values.keys.toList(), actual.values.keys.toList())
    }

    @Test
    fun typedAccessorsReturnMatchingTypesOnly() {
        val nested = BsonDocument("answer" to BsonInt32(42))
        val array = BsonArray(listOf(BsonString("first")))
        val document =
            BsonDocument(
                "document" to nested,
                "array" to array,
                "string" to BsonString("mongongo"),
                "boolean" to BsonBoolean(true),
                "int32" to BsonInt32(7),
                "int64" to BsonInt64(9_000_000_000),
                "double" to BsonDouble(1.25),
                "null" to BsonNull
            )

        assertFalse(BsonDocument().contains("missing"))
        assertTrue(BsonDocument().isEmpty())
        assertTrue("null" in document)
        assertFalse(document.isEmpty())
        assertEquals(nested, document.getDocument("document"))
        assertEquals(array, document.getArray("array"))
        assertEquals("mongongo", document.getString("string"))
        assertEquals(true, document.getBoolean("boolean"))
        assertEquals(7, document.getInt32("int32"))
        assertEquals(9_000_000_000, document.getInt64("int64"))
        assertEquals(1.25, document.getDouble("double"))
        assertEquals(7L, document.getNumberAsLong("int32"))
        assertEquals(9_000_000_000, document.getNumberAsLong("int64"))

        assertNull(document.getDocument("array"))
        assertNull(document.getArray("document"))
        assertNull(document.getString("int32"))
        assertNull(document.getBoolean("missing"))
        assertNull(document.getInt32("int64"))
        assertNull(document.getInt64("int32"))
        assertNull(document.getDouble("int32"))
        assertNull(document.getNumberAsLong("double"))
        assertNull(document.getString("null"))
    }

    @Test
    fun plusAndWithoutReturnImmutableCopiesWithStableOrder() {
        val original = BsonDocument("first" to BsonString("a"), "second" to BsonString("b"))

        val added = original.plus("third", BsonString("c"))
        val replaced = added.plus("first", BsonString("updated"))
        val removed = replaced.without("second")

        assertEquals(listOf("first", "second"), original.values.keys.toList())
        assertNull(original["third"])
        assertEquals(listOf("first", "second", "third"), added.values.keys.toList())
        assertEquals(listOf("first", "second", "third"), replaced.values.keys.toList())
        assertEquals(BsonString("updated"), replaced["first"])
        assertEquals(listOf("first", "third"), removed.values.keys.toList())
        assertEquals(listOf("first", "second"), original.values.keys.toList())
    }

    @Test
    fun failsOnDocumentLengthTooShort() {
        assertDecodeFails("BSON document length must be at least 5 bytes", int32Bytes(4) + byteArrayOf(0))
    }

    @Test
    fun failsOnDocumentLengthBeyondInput() {
        assertDecodeFails("BSON document length exceeds input size", int32Bytes(6) + byteArrayOf(0))
    }

    @Test
    fun failsOnUnterminatedCString() {
        assertDecodeFails(
            "Unterminated BSON cstring",
            int32Bytes(7) + byteArrayOf(0x0a, 'x'.code.toByte(), 'y'.code.toByte())
        )
    }

    @Test
    fun failsOnUnterminatedString() {
        assertDecodeFails(
            "Unterminated BSON string",
            documentBytes(
                elementBytes(
                    type = 0x02,
                    name = "name",
                    value = int32Bytes(4) + byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 1)
                )
            )
        )
    }

    @Test
    fun failsOnUnsupportedBsonType() {
        assertDecodeFails(
            "Unsupported BSON type 0x13",
            documentBytes(elementBytes(type = 0x13, name = "unsupported"))
        )
    }

    @Test
    fun failsOnMissingArrayIndex() {
        val malformedArray =
            documentBytes(
                int32ElementBytes("0", 1),
                int32ElementBytes("2", 3)
            )

        assertDecodeFails(
            "BSON array is missing index 1",
            documentBytes(elementBytes(type = 0x04, name = "items", value = malformedArray))
        )
    }

    @Test
    fun failsOnInvalidArrayIndex() {
        val malformedArray = documentBytes(int32ElementBytes("name", 1))

        assertDecodeFails(
            "BSON array index name is invalid",
            documentBytes(elementBytes(type = 0x04, name = "items", value = malformedArray))
        )
    }

    @Test
    fun failsOnOutOfOrderArrayIndex() {
        val malformedArray =
            documentBytes(
                int32ElementBytes("1", 2),
                int32ElementBytes("0", 1)
            )

        assertDecodeFails(
            "BSON array index 1 is out of order, expected 0",
            documentBytes(elementBytes(type = 0x04, name = "items", value = malformedArray))
        )
    }
}

private fun assertDecodeFails(expectedMessage: String, bytes: ByteArray) {
    val failure = assertFailsWith<IllegalArgumentException> { BsonCodec.decodeDocument(bytes) }
    assertTrue(
        failure.message?.contains(expectedMessage) == true,
        "Expected failure message to contain <$expectedMessage> but was <${failure.message}>"
    )
}

private fun documentBytes(vararg elements: ByteArray): ByteArray {
    val length = 4 + elements.sumOf { it.size } + 1
    val bytes = mutableListOf<Byte>()
    bytes.addAll(int32Bytes(length).toList())
    for (element in elements) {
        bytes.addAll(element.toList())
    }
    bytes.add(0)
    return bytes.toByteArray()
}

private fun int32ElementBytes(name: String, value: Int): ByteArray =
    elementBytes(type = 0x10, name = name, value = int32Bytes(value))

private fun elementBytes(type: Int, name: String, value: ByteArray = byteArrayOf()): ByteArray =
    byteArrayOf(type.toByte()) + cstringBytes(name) + value

private fun cstringBytes(value: String): ByteArray = value.encodeToByteArray() + byteArrayOf(0)

private fun int32Bytes(value: Int): ByteArray =
    ByteArray(4) { index -> (value ushr (index * 8)).toByte() }
