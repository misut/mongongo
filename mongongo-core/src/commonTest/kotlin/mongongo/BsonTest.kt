package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals

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

        assertEquals(document, BsonCodec.decodeDocument(BsonCodec.encode(document)))
    }
}
