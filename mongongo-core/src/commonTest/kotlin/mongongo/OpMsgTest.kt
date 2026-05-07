package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals

class OpMsgTest {
    @Test
    fun encodesAndDecodesKindZeroOpMsgFrames() {
        val body =
            BsonDocument(
                "ping" to BsonInt32(1),
                "\$db" to BsonString("admin")
            )

        val bytes = OpMsg.encode(requestId = 99, responseTo = 0, body = body)

        assertEquals(bytes.size, readInt32(bytes, 0))
        assertEquals(99, readInt32(bytes, 4))
        assertEquals(0, readInt32(bytes, 8))
        assertEquals(2013, readInt32(bytes, 12))
        assertEquals(0, readInt32(bytes, 16))
        assertEquals(0, bytes[20].toInt())

        val decoded = OpMsg.decode(bytes)
        assertEquals(99, decoded.requestId)
        assertEquals(0, decoded.responseTo)
        assertEquals(body, decoded.body)
    }
}
