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

public data class UpdateResult(
    val acknowledged: Boolean,
    val matchedCount: Long,
    val modifiedCount: Long,
    val upsertedId: BsonValue?,
    val raw: BsonDocument
)

public data class MongoServerDescription(
    val maxWireVersion: Int?,
    val maxMessageSizeBytes: Int?,
    val maxBsonObjectSize: Int?,
    val isWritablePrimary: Boolean
)

internal data class MongoCursorBatch(
    val id: Long,
    val documents: List<BsonDocument>
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
    private val transport: MongoTransport,
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

    internal suspend fun updateOne(
        database: String,
        collection: String,
        filter: BsonDocument,
        update: BsonDocument,
        upsert: Boolean
    ): UpdateResult {
        requireUpdateOperatorDocument(update)
        return runSingleUpdate(
            database = database,
            collection = collection,
            filter = filter,
            update = update,
            upsert = upsert
        )
    }

    internal suspend fun replaceOne(
        database: String,
        collection: String,
        filter: BsonDocument,
        replacement: BsonDocument,
        upsert: Boolean
    ): UpdateResult {
        requireReplacementDocument(replacement)
        return runSingleUpdate(
            database = database,
            collection = collection,
            filter = filter,
            update = replacement,
            upsert = upsert
        )
    }

    private suspend fun runSingleUpdate(
        database: String,
        collection: String,
        filter: BsonDocument,
        update: BsonDocument,
        upsert: Boolean
    ): UpdateResult {
        val result =
            runCommand(
                BsonDocument(
                    "update" to BsonString(collection),
                    "updates" to
                        BsonArray(
                            listOf(
                                BsonDocument(
                                    "q" to filter,
                                    "u" to update,
                                    "multi" to BsonBoolean(false),
                                    "upsert" to BsonBoolean(upsert)
                                )
                            )
                        ),
                    "ordered" to BsonBoolean(true),
                    "\$db" to BsonString(database)
                )
            )

        result.raw.throwIfWriteFailed()
        val matchedCount =
            result.raw.longValue("n")
                ?: error("MongoDB update response did not contain numeric n")
        val modifiedCount =
            result.raw.longValue("nModified")
                ?: error("MongoDB update response did not contain numeric nModified")
        return UpdateResult(
            acknowledged = true,
            matchedCount = matchedCount,
            modifiedCount = modifiedCount,
            upsertedId = result.raw.firstUpsertedId(),
            raw = result.raw
        )
    }

    internal suspend fun find(
        database: String,
        collection: String,
        filter: BsonDocument,
        limit: Int,
        batchSize: Int?
    ): MongoCursor {
        require(limit >= 0) { "MongoDB find limit cannot be negative" }
        require(batchSize == null || batchSize >= 0) { "MongoDB find batchSize cannot be negative" }

        val command = linkedMapOf<String, BsonValue>()
        command["find"] = BsonString(collection)
        command["filter"] = filter
        if (limit > 0) {
            command["limit"] = BsonInt32(limit)
        }
        if (batchSize != null) {
            command["batchSize"] = BsonInt32(batchSize)
        }
        command["\$db"] = BsonString(database)

        val result = runCommand(BsonDocument(command))
        val batch = result.raw.cursorBatch("firstBatch")
        return MongoCursor(
            client = this,
            database = database,
            collection = collection,
            initialCursorId = batch.id,
            initialBatch = batch.documents
        )
    }

    internal suspend fun getMore(database: String, collection: String, cursorId: Long): MongoCursorBatch {
        val result =
            runCommand(
                BsonDocument(
                    "getMore" to BsonInt64(cursorId),
                    "collection" to BsonString(collection),
                    "\$db" to BsonString(database)
                )
            )
        return result.raw.cursorBatch("nextBatch")
    }

    internal suspend fun killCursors(database: String, collection: String, cursorId: Long) {
        runCommand(
            BsonDocument(
                "killCursors" to BsonString(collection),
                "cursors" to BsonArray(listOf(BsonInt64(cursorId))),
                "\$db" to BsonString(database)
            )
        )
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
        public suspend fun connect(uri: String): MongoClient =
            connect(uri = uri, nonceGenerator = SecureMongoNonceGenerator)

        internal suspend fun connect(
            uri: String,
            nonceGenerator: MongoNonceGenerator,
            dnsResolver: MongoDnsResolver = SystemMongoDnsResolver,
            transportConnector: MongoTransportConnector = KtorMongoTransportConnector
        ): MongoClient {
            val connectionString = MongoConnectionStringParser.parse(uri = uri, dnsResolver = dnsResolver)
            var fallback: MongoClient? = null
            var lastFailure: Throwable? = null
            val attempts = if (connectionString.hosts.size > 1) WritablePrimaryRetryAttempts else 1

            repeat(attempts) { attempt ->
                for (host in connectionString.hosts) {
                    val transport =
                        try {
                            transportConnector.connect(host, tlsEnabled = connectionString.tlsEnabled)
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

                        val nextRequestId =
                            connectionString.credential?.let { credential ->
                                transport.authenticateScramSha256(
                                    credential = credential,
                                    nextRequestId = 2,
                                    nonceGenerator = nonceGenerator
                                )
                            } ?: 2
                        val candidate =
                            MongoClient(
                                connectionString = connectionString.redactedUri,
                                serverDescription = hello.toServerDescription(),
                                transport = transport,
                                nextRequestId = nextRequestId
                            )
                        if (candidate.serverDescription.isWritablePrimary) {
                            fallback?.close()
                            return candidate
                        }

                        if (fallback == null) {
                            fallback = candidate
                        } else {
                            try {
                                transport.close()
                            } catch (closeFailure: Throwable) {
                                lastFailure = closeFailure
                            }
                        }
                    } catch (throwable: Throwable) {
                        try {
                            transport.close()
                        } catch (closeFailure: Throwable) {
                            throwable.addSuppressed(closeFailure)
                        }
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

public class MongoCursor internal constructor(
    private val client: MongoClient,
    private val database: String,
    private val collection: String,
    initialCursorId: Long,
    initialBatch: List<BsonDocument>
) {
    private var cursorId = initialCursorId
    private val batch = ArrayDeque(initialBatch)
    private var closed = false

    public suspend fun next(): BsonDocument? {
        if (closed) {
            return null
        }

        while (true) {
            if (batch.isNotEmpty()) {
                return batch.removeFirst()
            }

            if (cursorId == 0L) {
                closed = true
                return null
            }

            val nextBatch = client.getMore(database = database, collection = collection, cursorId = cursorId)
            cursorId = nextBatch.id
            batch.addAll(nextBatch.documents)
        }
    }

    public suspend fun toList(): List<BsonDocument> {
        val documents = mutableListOf<BsonDocument>()
        while (true) {
            val document = next() ?: break
            documents.add(document)
        }
        return documents
    }

    public suspend fun close() {
        if (closed) {
            return
        }

        closed = true
        val id = cursorId
        cursorId = 0L
        batch.clear()
        if (id != 0L) {
            client.killCursors(database = database, collection = collection, cursorId = id)
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

    public suspend fun updateOne(
        filter: BsonDocument,
        update: BsonDocument,
        upsert: Boolean = false
    ): UpdateResult =
        database.client.updateOne(
            database = database.name,
            collection = name,
            filter = filter,
            update = update,
            upsert = upsert
        )

    public suspend fun replaceOne(
        filter: BsonDocument,
        replacement: BsonDocument,
        upsert: Boolean = false
    ): UpdateResult =
        database.client.replaceOne(
            database = database.name,
            collection = name,
            filter = filter,
            replacement = replacement,
            upsert = upsert
        )

    public suspend fun findOne(filter: BsonDocument = BsonDocument()): BsonDocument? =
        database.client.findOne(database = database.name, collection = name, filter = filter)

    public suspend fun find(
        filter: BsonDocument = BsonDocument(),
        limit: Int = 0,
        batchSize: Int? = null
    ): MongoCursor =
        database.client.find(
            database = database.name,
            collection = name,
            filter = filter,
            limit = limit,
            batchSize = batchSize
        )
}

private fun BsonDocument.toServerDescription(): MongoServerDescription =
    MongoServerDescription(
        maxWireVersion = intValue("maxWireVersion"),
        maxMessageSizeBytes = intValue("maxMessageSizeBytes"),
        maxBsonObjectSize = intValue("maxBsonObjectSize"),
        isWritablePrimary = getBoolean("isWritablePrimary") ?: getBoolean("ismaster") ?: false
    )

private fun BsonDocument.okValue(): Double =
    when (val ok = this["ok"]) {
        is BsonDouble -> ok.value
        is BsonInt32 -> ok.value.toDouble()
        is BsonInt64 -> ok.value.toDouble()
        else -> throw IllegalStateException("MongoDB command response did not contain numeric ok")
    }

private fun BsonDocument.intValue(name: String): Int? =
    getInt32(name) ?: getInt64(name)?.toInt() ?: getDouble(name)?.toInt()

private fun BsonDocument.longValue(name: String): Long? = getNumberAsLong(name)

private fun BsonDocument.documentValue(name: String): BsonDocument =
    getDocument(name) ?: error("MongoDB response field $name was not a document")

private fun BsonDocument.arrayValue(name: String): BsonArray =
    getArray(name) ?: error("MongoDB response field $name was not an array")

private fun BsonDocument.cursorBatch(batchName: String): MongoCursorBatch {
    val cursor = documentValue("cursor")
    val cursorId =
        cursor.longValue("id")
            ?: error("MongoDB cursor response did not contain a numeric cursor id")
    val batch = cursor.arrayValue(batchName)
    val documents =
        batch.values.map { value ->
            value as? BsonDocument ?: error("MongoDB cursor response $batchName contained a non-document value")
        }
    return MongoCursorBatch(id = cursorId, documents = documents)
}

private fun requireUpdateOperatorDocument(update: BsonDocument) {
    val firstField = update.values.keys.firstOrNull()
    require(firstField != null && firstField.startsWith("\$")) {
        "updateOne only supports update operator documents"
    }
}

private fun requireReplacementDocument(replacement: BsonDocument) {
    val firstField = replacement.values.keys.firstOrNull()
    require(firstField == null || !firstField.startsWith("\$")) {
        "replaceOne only supports replacement documents"
    }
}

private fun BsonDocument.firstUpsertedId(): BsonValue? {
    val upserted = getArray("upserted") ?: return null
    val first = upserted.values.firstOrNull() ?: return null
    val document = first as? BsonDocument ?: error("MongoDB update response upserted entry was not a document")
    return document["_id"]
}

private fun BsonDocument.throwIfWriteFailed() {
    val writeErrors = getArray("writeErrors")
    if (writeErrors != null && writeErrors.values.isNotEmpty()) {
        throw MongoWriteException(this)
    }
    if (contains("writeConcernError")) {
        throw MongoWriteException(this)
    }
}

private fun BsonDocument.writeFailureSummary(): String =
    when {
        getArray("writeErrors")?.values?.isNotEmpty() == true -> " with writeErrors"
        contains("writeConcernError") -> " with writeConcernError"
        else -> ""
    }

private fun BsonDocument.commandFailureSummary(): String {
    val code = intValue("code")
    val errmsg = getString("errmsg")
    return when {
        code != null && errmsg != null -> " code=$code errmsg=$errmsg"
        code != null -> " code=$code"
        errmsg != null -> " errmsg=$errmsg"
        else -> ""
    }
}
