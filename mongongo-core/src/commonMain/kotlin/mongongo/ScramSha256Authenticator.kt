@file:OptIn(ExperimentalEncodingApi::class)

package mongongo

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

public class MongoAuthenticationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

internal fun interface MongoNonceGenerator {
    fun generateNonce(): String
}

internal object SecureMongoNonceGenerator : MongoNonceGenerator {
    override fun generateNonce(): String =
        Base64.Default.encode(CryptographyRandom.Default.nextBytes(24))
}

internal suspend fun MongoTransport.authenticateScramSha256(
    credential: MongoCredential,
    nextRequestId: Int,
    nonceGenerator: MongoNonceGenerator = SecureMongoNonceGenerator
): Int {
    val conversation = ScramSha256Conversation.start(credential, nonceGenerator.generateNonce())
    var requestId = nextRequestId

    val start =
        sendAuthenticationCommand(
            requestId = requestId++,
            body =
                BsonDocument(
                    "saslStart" to BsonInt32(1),
                    "mechanism" to BsonString(ScramSha256Mechanism),
                    "payload" to BsonBinary(subtype = 0, bytes = conversation.clientFirstMessage.encodeToByteArray().toList()),
                    "options" to BsonDocument("skipEmptyExchange" to BsonBoolean(true)),
                    "\$db" to BsonString(credential.authSource)
                )
        )

    val conversationId = start.requiredInt32("conversationId")
    val serverFirst = start.requiredPayloadString()
    val clientFinal = conversation.receiveServerFirst(serverFirst)

    val continued =
        sendAuthenticationCommand(
            requestId = requestId++,
            body =
                BsonDocument(
                    "saslContinue" to BsonInt32(1),
                    "conversationId" to BsonInt32(conversationId),
                    "payload" to BsonBinary(subtype = 0, bytes = clientFinal.encodeToByteArray().toList()),
                    "\$db" to BsonString(credential.authSource)
                )
        )
    val serverFinal = continued.requiredPayloadString()
    conversation.verifyServerFinal(serverFinal)

    if (continued.getBoolean("done") != true) {
        val completed =
            sendAuthenticationCommand(
                requestId = requestId++,
                body =
                    BsonDocument(
                        "saslContinue" to BsonInt32(1),
                        "conversationId" to BsonInt32(conversationId),
                        "payload" to BsonBinary(subtype = 0, bytes = emptyList()),
                        "\$db" to BsonString(credential.authSource)
                    )
            )
        if (completed.getBoolean("done") != true) {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 authentication did not complete")
        }
    }

    return requestId
}

private suspend fun MongoTransport.sendAuthenticationCommand(requestId: Int, body: BsonDocument): BsonDocument {
    val response =
        try {
            send(requestId = requestId, body = body)
        } catch (throwable: MongoAuthenticationException) {
            throw throwable
        } catch (throwable: Throwable) {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 authentication failed", throwable)
        }

    val ok = response.okNumberOrNull()
    if (ok != 1.0) {
        val code = response.getInt32("code") ?: response.getInt64("code")?.toInt()
        val errmsg = response.getString("errmsg")
        val summary =
            when {
                code != null && errmsg != null -> " code=$code errmsg=$errmsg"
                code != null -> " code=$code"
                errmsg != null -> " errmsg=$errmsg"
                else -> ""
            }
        throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 authentication command failed$summary")
    }
    return response
}

internal class ScramSha256Conversation private constructor(
    private val credential: MongoCredential,
    private val clientNonce: String,
    private val clientFirstBare: String
) {
    val clientFirstMessage: String = "$Gs2Header$clientFirstBare"
    private var expectedServerSignature: ByteArray? = null

    suspend fun receiveServerFirst(message: String): String {
        val attributes = parseScramAttributes(message)
        if ("m" in attributes) {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 server sent an unsupported mandatory extension")
        }
        val serverNonce = attributes["r"] ?: throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 server nonce is missing")
        if (!serverNonce.startsWith(clientNonce) || serverNonce.length == clientNonce.length) {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 server nonce did not extend the client nonce")
        }

        val salt =
            try {
                Base64.Default.decode(attributes["s"] ?: throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 salt is missing"))
            } catch (throwable: IllegalArgumentException) {
                throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 salt was not valid base64", throwable)
            }
        val iterations =
            attributes["i"]?.toIntOrNull()
                ?: throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 iteration count is missing or invalid")
        if (iterations < MinimumScramIterationCount) {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 iteration count $iterations is below $MinimumScramIterationCount")
        }

        val clientFinalWithoutProof = "c=$ChannelBinding,r=$serverNonce"
        val authMessage = "$clientFirstBare,$message,$clientFinalWithoutProof"
        val saltedPassword =
            MongoScramSha256Crypto.pbkdf2Sha256(
                password = credential.preparedPasswordBytes(),
                salt = salt,
                iterations = iterations
            )
        val clientKey = MongoScramSha256Crypto.hmacSha256(saltedPassword, ClientKeyMessage)
        val storedKey = MongoScramSha256Crypto.sha256(clientKey)
        val clientSignature = MongoScramSha256Crypto.hmacSha256(storedKey, authMessage.encodeToByteArray())
        val clientProof = clientKey.xor(clientSignature)
        val serverKey = MongoScramSha256Crypto.hmacSha256(saltedPassword, ServerKeyMessage)
        expectedServerSignature = MongoScramSha256Crypto.hmacSha256(serverKey, authMessage.encodeToByteArray())

        return "$clientFinalWithoutProof,p=${Base64.Default.encode(clientProof)}"
    }

    fun verifyServerFinal(message: String) {
        val attributes = parseScramAttributes(message)
        val error = attributes["e"]
        if (error != null) {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 server rejected authentication: $error")
        }

        val actualSignature =
            try {
                Base64.Default.decode(
                    attributes["v"] ?: throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 server signature is missing")
                )
            } catch (throwable: IllegalArgumentException) {
                throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 server signature was not valid base64", throwable)
            }
        val expectedSignature =
            expectedServerSignature
                ?: throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 server signature arrived before server-first")
        if (!constantTimeEquals(expectedSignature, actualSignature)) {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 server signature did not match")
        }
    }

    internal companion object {
        fun start(credential: MongoCredential, clientNonce: String): ScramSha256Conversation {
            require(clientNonce.isNotEmpty()) { "MongoDB SCRAM-SHA-256 client nonce cannot be blank" }
            require(',' !in clientNonce) { "MongoDB SCRAM-SHA-256 client nonce cannot contain commas" }
            val clientFirstBare = "n=${scramEscape(credential.username)},r=$clientNonce"
            return ScramSha256Conversation(
                credential = credential,
                clientNonce = clientNonce,
                clientFirstBare = clientFirstBare
            )
        }
    }
}

internal object MongoScramSha256Crypto {
    private val provider: CryptographyProvider
        get() = CryptographyProvider.Default

    suspend fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray =
        provider
            .get(PBKDF2)
            .secretDerivation(SHA256, iterations = iterations, outputSize = 32.bytes, salt = salt)
            .deriveSecretToByteArray(password)

    suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        provider
            .get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignature(data)

    suspend fun sha256(data: ByteArray): ByteArray =
        provider
            .get(SHA256)
            .hasher()
            .hash(data)
}

private const val MinimumScramIterationCount = 4096
private const val Gs2Header = "n,,"
private val ChannelBinding = Base64.Default.encode(Gs2Header.encodeToByteArray())
private val ClientKeyMessage = "Client Key".encodeToByteArray()
private val ServerKeyMessage = "Server Key".encodeToByteArray()

private fun MongoCredential.preparedPasswordBytes(): ByteArray {
    if (password.any { it.code !in 0x00..0x7f }) {
        throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 v0 supports only ASCII passwords")
    }
    if ('\u0000' in password) {
        throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 password cannot contain null bytes")
    }
    return password.encodeToByteArray()
}

private fun scramEscape(value: String): String =
    buildString {
        for (character in value) {
            when (character) {
                ',' -> append("=2C")
                '=' -> append("=3D")
                else -> append(character)
            }
        }
    }

private fun parseScramAttributes(message: String): Map<String, String> {
    if (message.isEmpty()) {
        return emptyMap()
    }
    val attributes = linkedMapOf<String, String>()
    for (part in message.split(',')) {
        if (part.length < 3 || part[1] != '=') {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 message contained a malformed attribute")
        }
        val name = part.substring(0, 1)
        if (name in attributes) {
            throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 message contained a duplicate $name attribute")
        }
        attributes[name] = part.substring(2)
    }
    return attributes
}

private fun BsonDocument.requiredInt32(name: String): Int =
    getInt32(name) ?: throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 response field $name was not an int32")

private fun BsonDocument.requiredPayloadString(): String {
    val payload = this["payload"] as? BsonBinary
        ?: throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 response payload was not binary")
    if (payload.subtype != 0) {
        throw MongoAuthenticationException("MongoDB SCRAM-SHA-256 response payload used unsupported BSON binary subtype")
    }
    return payload.bytes.toByteArray().decodeToString()
}

private fun BsonDocument.okNumberOrNull(): Double? =
    when (val ok = this["ok"]) {
        is BsonDouble -> ok.value
        is BsonInt32 -> ok.value.toDouble()
        is BsonInt64 -> ok.value.toDouble()
        else -> null
    }

private fun ByteArray.xor(other: ByteArray): ByteArray {
    require(size == other.size) { "Cannot xor byte arrays with different lengths" }
    return ByteArray(size) { index -> (this[index].toInt() xor other[index].toInt()).toByte() }
}

private fun constantTimeEquals(expected: ByteArray, actual: ByteArray): Boolean {
    if (expected.size != actual.size) {
        return false
    }
    var difference = 0
    for (index in expected.indices) {
        difference = difference or (expected[index].toInt() xor actual[index].toInt())
    }
    return difference == 0
}
