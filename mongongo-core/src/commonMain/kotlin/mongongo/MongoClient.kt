package mongongo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public data class MongoCommandResult(
    val ok: Double,
    val raw: BsonDocument
)

public data class MongoServerDescription(
    val maxWireVersion: Int?,
    val maxMessageSizeBytes: Int?,
    val maxBsonObjectSize: Int?,
    val isWritablePrimary: Boolean
)

public class MongoCommandException(
    public val result: BsonDocument
) : RuntimeException("MongoDB command failed with ok=${result.okValue()}")

public class MongoClient private constructor(
    public val connectionString: String,
    public val serverDescription: MongoServerDescription,
    private val transport: KtorMongoTransport,
    nextRequestId: Int
) {
    private val sendMutex = Mutex()
    private var nextRequestId = nextRequestId
    private var closed = false

    public suspend fun ping(database: String = "admin"): MongoCommandResult {
        require(database.isNotBlank()) { "MongoDB database name cannot be blank" }
        return runCommand(
            BsonDocument(
                "ping" to BsonInt32(1),
                "\$db" to BsonString(database)
            )
        )
    }

    public suspend fun close() {
        if (closed) {
            return
        }
        closed = true
        transport.close()
    }

    private suspend fun runCommand(command: BsonDocument): MongoCommandResult {
        check(!closed) { "MongoClient is closed" }

        val raw =
            sendMutex.withLock {
                transport.send(requestId = nextRequestId++, body = command)
            }
        val ok = raw.okValue()
        if (ok != 1.0) {
            throw MongoCommandException(raw)
        }

        return MongoCommandResult(ok = ok, raw = raw)
    }

    public companion object {
        public suspend fun connect(uri: String): MongoClient {
            val connectionString = MongoConnectionStringParser.parse(uri)
            val transport = KtorMongoTransport.connect(connectionString.primaryHost)

            try {
                val hello =
                    transport.send(
                        requestId = 1,
                        body =
                            BsonDocument(
                                "hello" to BsonInt32(1),
                                "\$db" to BsonString("admin")
                            )
                    )
                val ok = hello.okValue()
                if (ok != 1.0) {
                    throw MongoCommandException(hello)
                }

                return MongoClient(
                    connectionString = uri,
                    serverDescription = hello.toServerDescription(),
                    transport = transport,
                    nextRequestId = 2
                )
            } catch (throwable: Throwable) {
                transport.close()
                throw throwable
            }
        }
    }
}

private fun BsonDocument.toServerDescription(): MongoServerDescription =
    MongoServerDescription(
        maxWireVersion = intValue("maxWireVersion"),
        maxMessageSizeBytes = intValue("maxMessageSizeBytes"),
        maxBsonObjectSize = intValue("maxBsonObjectSize"),
        isWritablePrimary = booleanValue("isWritablePrimary") ?: booleanValue("ismaster") ?: false
    )

private fun BsonDocument.okValue(): Double =
    when (val ok = this["ok"]) {
        is BsonDouble -> ok.value
        is BsonInt32 -> ok.value.toDouble()
        is BsonInt64 -> ok.value.toDouble()
        else -> throw IllegalStateException("MongoDB command response did not contain numeric ok")
    }

private fun BsonDocument.intValue(name: String): Int? =
    when (val value = this[name]) {
        is BsonInt32 -> value.value
        is BsonInt64 -> value.value.toInt()
        is BsonDouble -> value.value.toInt()
        else -> null
    }

private fun BsonDocument.booleanValue(name: String): Boolean? = (this[name] as? BsonBoolean)?.value
