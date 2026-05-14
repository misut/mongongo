package mongongo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MongoAuthenticationTest {
    @Test
    fun authenticatesBeforeRunningCommandsAgainstFakeServer() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                expectScramSha256Authentication()
                val ping = receive()
                assertEquals(BsonInt32(1), ping.body["ping"])
                assertEquals(BsonString("admin"), ping.body["\$db"])
                reply(ping, BsonDocument("ok" to BsonDouble(1.0)))
            }
        ) { baseUri ->
            val client =
                MongoClient.connect(
                    uri = authUri(baseUri),
                    nonceGenerator = MongoNonceGenerator { "fixed-client-nonce" }
                )
            try {
                assertFalse(client.connectionString.contains("user"))
                assertFalse(client.connectionString.contains("pencil"))
                assertTrue(client.connectionString.startsWith("mongodb://<credentials>@"))
                assertEquals(1.0, client.ping().ok)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun supportsLongScramConversationAgainstFakeServer() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                expectScramSha256Authentication(doneOnProof = false)
            }
        ) { baseUri ->
            val client =
                MongoClient.connect(
                    uri = authUri(baseUri),
                    nonceGenerator = MongoNonceGenerator { "fixed-client-nonce" }
                )
            client.close()
        }
    }

    @Test
    fun failsAuthenticationOnServerNonceMismatch() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val start = receive()
                reply(
                    start,
                    BsonDocument(
                        "conversationId" to BsonInt32(1),
                        "payload" to
                            BsonBinary(
                                subtype = 0,
                                bytes = "r=other-nonce,s=c2FsdA==,i=4096".encodeToByteArray().toList()
                            ),
                        "done" to BsonBoolean(false),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { baseUri ->
            assertFailsWith<MongoAuthenticationException> {
                MongoClient.connect(
                    uri = authUri(baseUri),
                    nonceGenerator = MongoNonceGenerator { "fixed-client-nonce" }
                )
            }
        }
    }

    @Test
    fun failsAuthenticationOnServerSignatureMismatch() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val start = receive()
                val clientFirst = (start.body["payload"] as BsonBinary).bytes.toByteArray().decodeToString()
                val clientNonce = clientFirst.substringAfter("r=")
                val serverFirst = "r=${clientNonce}server,s=c2FsdA==,i=4096"
                reply(
                    start,
                    BsonDocument(
                        "conversationId" to BsonInt32(1),
                        "payload" to BsonBinary(subtype = 0, bytes = serverFirst.encodeToByteArray().toList()),
                        "done" to BsonBoolean(false),
                        "ok" to BsonDouble(1.0)
                    )
                )
                val proof = receive()
                reply(
                    proof,
                    BsonDocument(
                        "conversationId" to BsonInt32(1),
                        "payload" to
                            BsonBinary(
                                subtype = 0,
                                bytes = "v=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".encodeToByteArray().toList()
                            ),
                        "done" to BsonBoolean(true),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { baseUri ->
            assertFailsWith<MongoAuthenticationException> {
                MongoClient.connect(
                    uri = authUri(baseUri),
                    nonceGenerator = MongoNonceGenerator { "fixed-client-nonce" }
                )
            }
        }
    }

    @Test
    fun failsAuthenticationOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val start = receive()
                reply(
                    start,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(18),
                        "codeName" to BsonString("AuthenticationFailed"),
                        "errmsg" to BsonString("Authentication failed"),
                        "errorLabels" to BsonArray(listOf(BsonString("HandshakeError")))
                    )
                )
            }
        ) { baseUri ->
            val failure =
                assertFailsWith<MongoAuthenticationException> {
                    MongoClient.connect(
                        uri = authUri(baseUri),
                        nonceGenerator = MongoNonceGenerator { "fixed-client-nonce" }
                    )
                }
            assertEquals(18, failure.code)
            assertEquals("AuthenticationFailed", failure.codeName)
            assertEquals("Authentication failed", failure.errmsg)
            assertEquals(setOf("HandshakeError"), failure.errorLabels)
            assertTrue(failure.hasErrorLabel("HandshakeError"))
        }
    }

    @Test
    fun failsAuthenticationOnMalformedPayload() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val start = receive()
                reply(
                    start,
                    BsonDocument(
                        "conversationId" to BsonInt32(1),
                        "payload" to BsonString("not binary"),
                        "done" to BsonBoolean(false),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { baseUri ->
            assertFailsWith<MongoAuthenticationException> {
                MongoClient.connect(
                    uri = authUri(baseUri),
                    nonceGenerator = MongoNonceGenerator { "fixed-client-nonce" }
                )
            }
        }
    }
}
