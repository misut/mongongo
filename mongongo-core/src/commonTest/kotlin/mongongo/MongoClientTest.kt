package mongongo

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MongoClientTest {
    @Test
    fun connectsWithHelloAndRunsPingAgainstFakeServer() = runTest {
        val selectorManager = SelectorManager(Dispatchers.Default)
        val server = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
        val serverJob =
            launch(Dispatchers.Default) {
                val socket = server.accept()
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel()

                try {
                    val hello = OpMsg.decode(input.readMongoMessage())
                    assertEquals(BsonInt32(1), hello.body["hello"])
                    assertEquals(BsonString("admin"), hello.body["\$db"])
                    output.writeFully(
                        OpMsg.encode(
                            requestId = 1_000,
                            responseTo = hello.requestId,
                            body =
                                BsonDocument(
                                    "ok" to BsonDouble(1.0),
                                    "isWritablePrimary" to BsonBoolean(true),
                                    "maxWireVersion" to BsonInt32(21),
                                    "maxMessageSizeBytes" to BsonInt32(48_000_000),
                                    "maxBsonObjectSize" to BsonInt32(16_777_216)
                                )
                        )
                    )
                    output.flush()

                    val ping = OpMsg.decode(input.readMongoMessage())
                    assertEquals(BsonInt32(1), ping.body["ping"])
                    assertEquals(BsonString("admin"), ping.body["\$db"])
                    output.writeFully(
                        OpMsg.encode(
                            requestId = 1_001,
                            responseTo = ping.requestId,
                            body = BsonDocument("ok" to BsonDouble(1.0))
                        )
                    )
                    output.flush()
                } finally {
                    socket.close()
                }
            }

        try {
            val client = MongoClient.connect("mongodb://127.0.0.1:${server.port}")
            try {
                assertTrue(client.serverDescription.isWritablePrimary)
                assertEquals(21, client.serverDescription.maxWireVersion)
                assertEquals(1.0, client.ping().ok)
            } finally {
                client.close()
            }
            serverJob.join()
        } finally {
            server.close()
            selectorManager.close()
            serverJob.cancelAndJoin()
        }
    }
}
