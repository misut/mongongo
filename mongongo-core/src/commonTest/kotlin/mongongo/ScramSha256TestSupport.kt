@file:OptIn(ExperimentalEncodingApi::class)

package mongongo

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal suspend fun FakeMongoConnection.expectScramSha256Authentication(
    username: String = "user",
    password: String = "pencil",
    authSource: String = "admin",
    serverNonceSuffix: String = "server-nonce",
    salt: ByteArray = "fake-server-salt".encodeToByteArray(),
    iterations: Int = 4096,
    conversationId: Int = 7,
    doneOnProof: Boolean = true
) {
    val start = receive()
    assertEquals(BsonInt32(1), start.body["saslStart"])
    assertEquals(BsonString(ScramSha256Mechanism), start.body["mechanism"])
    assertEquals(BsonString(authSource), start.body["\$db"])
    assertEquals(BsonDocument("skipEmptyExchange" to BsonBoolean(true)), start.body["options"])

    val clientFirst = start.body.payloadString()
    assertTrue(clientFirst.startsWith("n,,"))
    val clientFirstBare = clientFirst.removePrefix("n,,")
    val clientFirstAttributes = parseTestScramAttributes(clientFirstBare)
    assertEquals(scramEscapedForTest(username), clientFirstAttributes["n"])
    val clientNonce = clientFirstAttributes["r"] ?: error("Client first SCRAM payload did not contain a nonce")
    val serverNonce = clientNonce + serverNonceSuffix
    val serverFirst = "r=$serverNonce,s=${Base64.Default.encode(salt)},i=$iterations"

    reply(
        start,
        BsonDocument(
            "conversationId" to BsonInt32(conversationId),
            "payload" to BsonBinary(subtype = 0, bytes = serverFirst.encodeToByteArray().toList()),
            "done" to BsonBoolean(false),
            "ok" to BsonDouble(1.0)
        )
    )

    val proof = receive()
    assertEquals(BsonInt32(1), proof.body["saslContinue"])
    assertEquals(BsonInt32(conversationId), proof.body["conversationId"])
    assertEquals(BsonString(authSource), proof.body["\$db"])
    val clientFinal = proof.body.payloadString()
    assertTrue(clientFinal.startsWith("c=biws,r=$serverNonce,p="))
    val clientFinalWithoutProof = clientFinal.substringBefore(",p=")
    val serverFinal =
        "v=" +
            Base64.Default.encode(
                serverSignature(
                    password = password,
                    salt = salt,
                    iterations = iterations,
                    authMessage = "$clientFirstBare,$serverFirst,$clientFinalWithoutProof"
                )
            )

    reply(
        proof,
        BsonDocument(
            "conversationId" to BsonInt32(conversationId),
            "payload" to BsonBinary(subtype = 0, bytes = serverFinal.encodeToByteArray().toList()),
            "done" to BsonBoolean(doneOnProof),
            "ok" to BsonDouble(1.0)
        )
    )

    if (!doneOnProof) {
        val empty = receive()
        assertEquals(BsonInt32(1), empty.body["saslContinue"])
        assertEquals(BsonInt32(conversationId), empty.body["conversationId"])
        assertEquals("", empty.body.payloadString())
        reply(
            empty,
            BsonDocument(
                "conversationId" to BsonInt32(conversationId),
                "payload" to BsonBinary(subtype = 0, bytes = emptyList()),
                "done" to BsonBoolean(true),
                "ok" to BsonDouble(1.0)
            )
        )
    }
}

internal fun authUri(baseUri: String, database: String = "app", authSource: String = "admin"): String =
    baseUri.replace("mongodb://", "mongodb://user:pencil@") + "/$database?authSource=$authSource"

private suspend fun serverSignature(
    password: String,
    salt: ByteArray,
    iterations: Int,
    authMessage: String
): ByteArray {
    val saltedPassword =
        MongoScramSha256Crypto.pbkdf2Sha256(
            password = password.encodeToByteArray(),
            salt = salt,
            iterations = iterations
        )
    val serverKey = MongoScramSha256Crypto.hmacSha256(saltedPassword, "Server Key".encodeToByteArray())
    return MongoScramSha256Crypto.hmacSha256(serverKey, authMessage.encodeToByteArray())
}

private fun BsonDocument.payloadString(): String {
    val payload = this["payload"] as? BsonBinary ?: error("Expected SCRAM payload to be binary")
    assertEquals(0, payload.subtype)
    return payload.bytes.toByteArray().decodeToString()
}

private fun parseTestScramAttributes(message: String): Map<String, String> =
    message
        .split(',')
        .associate { attribute ->
            attribute.substring(0, 1) to attribute.substring(2)
        }

private fun scramEscapedForTest(value: String): String =
    value
        .replace("=", "=3D")
        .replace(",", "=2C")
