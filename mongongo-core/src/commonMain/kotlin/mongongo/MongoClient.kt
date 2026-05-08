package mongongo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public data class MongoCommandResult(
    val ok: Double,
    val raw: BsonDocument
)

public data class InsertOneResult(
    val acknowledged: Boolean,
    val insertedId: BsonValue?,
    val raw: BsonDocument
)

public data class DeleteResult(
    val acknowledged: Boolean,
    val deletedCount: Long,
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
) : RuntimeException("MongoDB command failed with ok=${result.okValue()}${result.commandFailureSummary()}")

public class MongoWriteException(
    public val result: BsonDocument
) : RuntimeException("MongoDB write failed${result.writeFailureSummary()}")

private const val WritablePrimaryRetryAttempts = 120
private const val WritablePrimaryRetryDelayMilliseconds = 100L

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

    public fun database(name: String): MongoDatabase {
        require(name.isNotBlank()) { "MongoDB database name cannot be blank" }
        return MongoDatabase(client = this, name = name)
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

    internal suspend fun insertOne(database: String, collection: String, document: BsonDocument): InsertOneResult {
        val existingId = document["_id"]
        val insertedId = existingId ?: BsonObjectId.generate()
        val documentToInsert = if (existingId == null) document.withValue("_id", insertedId) else document
        val result =
            runCommand(
                BsonDocument(
                    "insert" to BsonString(collection),
                    "documents" to BsonArray(listOf(documentToInsert)),
                    "ordered" to BsonBoolean(true),
                    "\$db" to BsonString(database)
                )
            )

        result.raw.throwIfWriteFailed()
        return InsertOneResult(acknowledged = true, insertedId = insertedId, raw = result.raw)
    }

    internal suspend fun deleteOne(database: String, collection: String, filter: BsonDocument): DeleteResult {
        val result =
            runCommand(
                BsonDocument(
                    "delete" to BsonString(collection),
                    "deletes" to
                        BsonArray(
                            listOf(
                                BsonDocument(
                                    "q" to filter,
                                    "limit" to BsonInt32(1)
                                )
                            )
                        ),
                    "ordered" to BsonBoolean(true),
                    "\$db" to BsonString(database)
                )
            )

        result.raw.throwIfWriteFailed()
        val deletedCount =
            result.raw.longValue("n")
                ?: error("MongoDB delete response did not contain numeric n")
        return DeleteResult(acknowledged = true, deletedCount = deletedCount, raw = result.raw)
    }

    internal suspend fun findOne(database: String, collection: String, filter: BsonDocument): BsonDocument? {
        val result =
            runCommand(
                BsonDocument(
                    "find" to BsonString(collection),
                    "filter" to filter,
                    "limit" to BsonInt32(1),
                    "singleBatch" to BsonBoolean(true),
                    "\$db" to BsonString(database)
                )
            )
        val cursor = result.raw.documentValue("cursor")
        val cursorId = cursor.longValue("id")
            ?: error("MongoDB find response did not contain a numeric cursor id")
        val firstBatch = cursor.arrayValue("firstBatch")
        check(cursorId == 0L) { "MongoDB findOne response left cursor open id=$cursorId" }

        return when (val document = firstBatch.values.firstOrNull()) {
            null -> null
            is BsonDocument -> document
            else -> error("MongoDB find response firstBatch contained a non-document value")
        }
    }

    public companion object {
        public suspend fun connect(uri: String): MongoClient {
            val connectionString = MongoConnectionStringParser.parse(uri)
            var fallback: MongoClient? = null
            var lastFailure: Throwable? = null
            val attempts = if (connectionString.hosts.size > 1) WritablePrimaryRetryAttempts else 1

            repeat(attempts) { attempt ->
                for (host in connectionString.hosts) {
                    val transport =
                        try {
                            KtorMongoTransport.connect(host)
                        } catch (throwable: Throwable) {
                            lastFailure = throwable
                            continue
                        }

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

                        val candidate =
                            MongoClient(
                                connectionString = uri,
                                serverDescription = hello.toServerDescription(),
                                transport = transport,
                                nextRequestId = 2
                            )
                        if (candidate.serverDescription.isWritablePrimary) {
                            fallback?.close()
                            return candidate
                        }

                        if (fallback == null) {
                            fallback = candidate
                        } else {
                            transport.close()
                        }
                    } catch (throwable: Throwable) {
                        transport.close()
                        lastFailure = throwable
                    }
                }

                if (attempt < attempts - 1) {
                    fallback?.close()
                    fallback = null
                    withContext(Dispatchers.Default) { delay(WritablePrimaryRetryDelayMilliseconds) }
                }
            }

            fallback?.let { return it }
            throw lastFailure ?: IllegalArgumentException("MongoDB connection string must contain at least one host")
        }
    }
}

public class MongoDatabase internal constructor(
    internal val client: MongoClient,
    public val name: String
) {
    init {
        require(name.isNotBlank()) { "MongoDB database name cannot be blank" }
    }

    public fun collection(name: String): MongoCollection {
        require(name.isNotBlank()) { "MongoDB collection name cannot be blank" }
        return MongoCollection(database = this, name = name)
    }
}

public class MongoCollection internal constructor(
    private val database: MongoDatabase,
    public val name: String
) {
    init {
        require(name.isNotBlank()) { "MongoDB collection name cannot be blank" }
    }

    public suspend fun insertOne(document: BsonDocument): InsertOneResult =
        database.client.insertOne(database = database.name, collection = name, document = document)

    public suspend fun deleteOne(filter: BsonDocument): DeleteResult =
        database.client.deleteOne(database = database.name, collection = name, filter = filter)

    public suspend fun findOne(filter: BsonDocument = BsonDocument()): BsonDocument? =
        database.client.findOne(database = database.name, collection = name, filter = filter)
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

private fun BsonDocument.longValue(name: String): Long? =
    when (val value = this[name]) {
        is BsonInt64 -> value.value
        is BsonInt32 -> value.value.toLong()
        else -> null
    }

private fun BsonDocument.booleanValue(name: String): Boolean? = (this[name] as? BsonBoolean)?.value

private fun BsonDocument.stringValue(name: String): String? = (this[name] as? BsonString)?.value

private fun BsonDocument.documentValue(name: String): BsonDocument =
    this[name] as? BsonDocument ?: error("MongoDB response field $name was not a document")

private fun BsonDocument.arrayValue(name: String): BsonArray =
    this[name] as? BsonArray ?: error("MongoDB response field $name was not an array")

private fun BsonDocument.throwIfWriteFailed() {
    val writeErrors = this["writeErrors"] as? BsonArray
    if (writeErrors != null && writeErrors.values.isNotEmpty()) {
        throw MongoWriteException(this)
    }
    if (this["writeConcernError"] != null) {
        throw MongoWriteException(this)
    }
}

private fun BsonDocument.writeFailureSummary(): String =
    when {
        (this["writeErrors"] as? BsonArray)?.values?.isNotEmpty() == true -> " with writeErrors"
        this["writeConcernError"] != null -> " with writeConcernError"
        else -> ""
    }

private fun BsonDocument.commandFailureSummary(): String {
    val code = intValue("code")
    val errmsg = stringValue("errmsg")
    return when {
        code != null && errmsg != null -> " code=$code errmsg=$errmsg"
        code != null -> " code=$code"
        errmsg != null -> " errmsg=$errmsg"
        else -> ""
    }
}
