package mongongo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MongoClientTest {
    @Test
    fun connectsWithHelloAndRunsPingAgainstFakeServer() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val ping = receive()
                assertEquals(BsonInt32(1), ping.body["ping"])
                assertEquals(BsonString("admin"), ping.body["\$db"])
                reply(ping, BsonDocument("ok" to BsonDouble(1.0)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertTrue(client.serverDescription.isWritablePrimary)
                assertEquals(21, client.serverDescription.maxWireVersion)
                assertEquals(1.0, client.ping().ok)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun closeIsIdempotentAndRejectsLaterOperations() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
            }
        ) { uri ->
            val client = MongoClient.connect(uri)

            client.close()
            client.close()

            assertFailsWith<IllegalStateException> {
                client.ping()
            }
        }
    }
}
