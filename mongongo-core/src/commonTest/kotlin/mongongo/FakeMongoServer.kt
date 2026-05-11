package mongongo

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertEquals

internal suspend fun TestScope.withFakeMongoServer(
    handler: suspend FakeMongoConnection.() -> Unit,
    block: suspend (String) -> Unit
) {
    val selectorManager = SelectorManager(Dispatchers.Default)
    val server = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
    val serverJob =
        async(Dispatchers.Default) {
            val socket = server.accept()
            val connection =
                FakeMongoConnection(
                    input = socket.openReadChannel(),
                    output = socket.openWriteChannel()
                )

            try {
                connection.handler()
            } finally {
                socket.close()
            }
        }

    try {
        block("mongodb://127.0.0.1:${server.port}")
        serverJob.await()
    } finally {
        server.close()
        selectorManager.close()
        serverJob.cancelAndJoin()
    }
}

internal class FakeMongoConnection(
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel
) {
    private var nextResponseId = 1_000

    suspend fun receive(): OpMsgFrame = OpMsg.decode(input.readMongoMessage())

    suspend fun expectHello(isWritablePrimary: Boolean = true): OpMsgFrame {
        val hello = receive()
        assertEquals(BsonInt32(1), hello.body["hello"])
        assertEquals(BsonString("admin"), hello.body["\$db"])
        reply(
            request = hello,
            body =
                BsonDocument(
                    "ok" to BsonDouble(1.0),
                    "isWritablePrimary" to BsonBoolean(isWritablePrimary),
                    "maxWireVersion" to BsonInt32(21),
                    "maxMessageSizeBytes" to BsonInt32(48_000_000),
                    "maxBsonObjectSize" to BsonInt32(16_777_216)
                )
        )
        return hello
    }

    suspend fun reply(request: OpMsgFrame, body: BsonDocument) {
        output.writeFully(
            OpMsg.encode(
                requestId = nextResponseId++,
                responseTo = request.requestId,
                body = body
            )
        )
        output.flush()
    }
}
