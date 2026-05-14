package mongongo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

public data class MongoCommandResult(
    val ok: Double,
    val raw: BsonDocument
)

public data class MongoErrorMetadata(
    val code: Int?,
    val codeName: String?,
    val errmsg: String?,
    val errorLabels: Set<String>
) {
    public fun hasErrorLabel(label: String): Boolean = label in errorLabels
}

public data class InsertOneResult(
    val acknowledged: Boolean,
    val insertedId: BsonValue?,
    val raw: BsonDocument
)

public data class InsertManyResult(
    val acknowledged: Boolean,
    val insertedIds: Map<Int, BsonValue>,
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
) : RuntimeException("MongoDB command failed with ok=${result.okValue()}${result.commandFailureSummary()}") {
    public val error: MongoErrorMetadata = result.commandErrorMetadata()
    public val code: Int? = error.code
    public val codeName: String? = error.codeName
    public val errmsg: String? = error.errmsg
    public val errorLabels: Set<String> = error.errorLabels

    public fun hasErrorLabel(label: String): Boolean = error.hasErrorLabel(label)
}

public class MongoWriteException(
    public val result: BsonDocument
) : RuntimeException("MongoDB write failed${result.writeFailureSummary()}") {
    public val writeError: MongoErrorMetadata? = result.firstWriteErrorMetadata()
    public val writeConcernError: MongoErrorMetadata? = result.writeConcernErrorMetadata()
    public val error: MongoErrorMetadata = writeError ?: writeConcernError ?: result.commandErrorMetadata()
    public val code: Int? = error.code
    public val codeName: String? = error.codeName
    public val errmsg: String? = error.errmsg
    public val errorLabels: Set<String> =
        result.errorLabels() + error.errorLabels + (writeError?.errorLabels ?: emptySet()) + (writeConcernError?.errorLabels ?: emptySet())

    public fun hasErrorLabel(label: String): Boolean = label in errorLabels
}

private const val WritablePrimaryRetryAttempts = 120
private const val WritablePrimaryRetryDelayMilliseconds = 100L

public class MongoClient private constructor(
    public val connectionString: String,
    public val serverDescription: MongoServerDescription,
    private val transport: MongoTransport,
    nextRequestId: Int
) {
    private val sendMutex = Mutex()
    private val operationContext = MongoClientOperationContext(this)
    private var nextRequestId = nextRequestId
    private var closed = false

    public suspend fun ping(database: String = "admin"): MongoCommandResult {
        require(database.isNotBlank()) { "MongoDB database name cannot be blank" }
        return operationContext.runCommand(
            BsonDocument(
                "ping" to BsonInt32(1),
                "\$db" to BsonString(database)
            )
        )
    }

    public fun database(name: String): MongoDatabase {
        require(name.isNotBlank()) { "MongoDB database name cannot be blank" }
        return MongoDatabase(context = operationContext, name = name)
    }

    public suspend fun startSession(): MongoSession {
        val result =
            operationContext.runCommand(
                BsonDocument(
                    "startSession" to BsonInt32(1),
                    "\$db" to BsonString("admin")
                )
            )
        val sessionId = result.raw.getDocument("id")
            ?: error("MongoDB startSession response did not contain session id")
        return MongoSession(client = this, sessionId = sessionId)
    }

    public suspend fun <R> withSession(block: suspend MongoSession.() -> R): R {
        val session = startSession()
        try {
            return session.block()
        } finally {
            session.close()
        }
    }

    public suspend fun <R> withTransaction(block: suspend MongoTransaction.() -> R): R =
        withSession { withTransaction(block) }

    internal suspend fun endSession(sessionId: BsonDocument) {
        sendCommand(
            BsonDocument(
                "endSessions" to BsonArray(listOf(sessionId)),
                "\$db" to BsonString("admin")
            )
        )
    }

    public suspend fun close() {
        sendMutex.withLock {
            if (closed) {
                return
            }
            closed = true
            transport.close()
        }
    }

    internal suspend fun sendCommand(command: BsonDocument): MongoCommandResult {
        val raw =
            sendMutex.withLock {
                check(!closed) { "MongoClient is closed" }
                transport.send(requestId = nextRequestId++, body = command)
            }
        val ok = raw.okValue()
        if (ok != 1.0) {
            throw MongoCommandException(raw)
        }

        return MongoCommandResult(ok = ok, raw = raw)
    }

    internal suspend fun insertOne(
        context: MongoOperationContext,
        database: String,
        collection: String,
        document: BsonDocument
    ): InsertOneResult {
        val result =
            insertMany(
                context = context,
                database = database,
                collection = collection,
                documents = listOf(document),
                ordered = true
            )
        return InsertOneResult(acknowledged = result.acknowledged, insertedId = result.insertedIds[0], raw = result.raw)
    }

    internal suspend fun insertMany(
        context: MongoOperationContext,
        database: String,
        collection: String,
        documents: List<BsonDocument>,
        ordered: Boolean
    ): InsertManyResult {
        require(documents.isNotEmpty()) { "insertMany requires at least one document" }

        val insertedIds = linkedMapOf<Int, BsonValue>()
        val documentsToInsert =
            documents.mapIndexed { index, document ->
                val existingId = document["_id"]
                val insertedId = existingId ?: BsonObjectId.generate()
                insertedIds[index] = insertedId
                if (existingId == null) document.withValue("_id", insertedId) else document
            }
        val result =
            context.runCommand(
                BsonDocument(
                    "insert" to BsonString(collection),
                    "documents" to BsonArray(documentsToInsert),
                    "ordered" to BsonBoolean(ordered),
                    "\$db" to BsonString(database)
                )
            )

        result.raw.throwIfWriteFailed()
        return InsertManyResult(acknowledged = true, insertedIds = insertedIds, raw = result.raw)
    }

    internal suspend fun deleteOne(
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument
    ): DeleteResult =
        delete(context = context, database = database, collection = collection, filter = filter, limit = 1, ordered = true)

    internal suspend fun deleteMany(
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument,
        ordered: Boolean
    ): DeleteResult =
        delete(context = context, database = database, collection = collection, filter = filter, limit = 0, ordered = ordered)

    private suspend fun delete(
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument,
        limit: Int,
        ordered: Boolean
    ): DeleteResult {
        val result =
            context.runCommand(
                BsonDocument(
                    "delete" to BsonString(collection),
                    "deletes" to
                        BsonArray(
                            listOf(
                                BsonDocument(
                                    "q" to filter,
                                    "limit" to BsonInt32(limit)
                                )
                            )
                        ),
                    "ordered" to BsonBoolean(ordered),
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
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument,
        update: BsonDocument,
        upsert: Boolean
    ): UpdateResult {
        requireUpdateOperatorDocument(update, operation = "updateOne")
        return runUpdate(
            context = context,
            database = database,
            collection = collection,
            filter = filter,
            update = update,
            multi = false,
            upsert = upsert
        )
    }

    internal suspend fun updateMany(
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument,
        update: BsonDocument,
        upsert: Boolean
    ): UpdateResult {
        requireUpdateOperatorDocument(update, operation = "updateMany")
        return runUpdate(
            context = context,
            database = database,
            collection = collection,
            filter = filter,
            update = update,
            multi = true,
            upsert = upsert
        )
    }

    internal suspend fun replaceOne(
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument,
        replacement: BsonDocument,
        upsert: Boolean
    ): UpdateResult {
        requireReplacementDocument(replacement)
        return runUpdate(
            context = context,
            database = database,
            collection = collection,
            filter = filter,
            update = replacement,
            multi = false,
            upsert = upsert
        )
    }

    private suspend fun runUpdate(
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument,
        update: BsonDocument,
        multi: Boolean,
        upsert: Boolean
    ): UpdateResult {
        val result =
            context.runCommand(
                BsonDocument(
                    "update" to BsonString(collection),
                    "updates" to
                        BsonArray(
                            listOf(
                                BsonDocument(
                                    "q" to filter,
                                    "u" to update,
                                    "multi" to BsonBoolean(multi),
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

    internal suspend fun <T : Any> find(
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument,
        limit: Int,
        batchSize: Int?,
        codec: MongoCodec<T>
    ): MongoCursor<T> {
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

        val result = context.runCommand(BsonDocument(command))
        val batch = result.raw.cursorBatch("firstBatch")
        return MongoCursor(
            context = context,
            database = database,
            collection = collection,
            initialCursorId = batch.id,
            initialBatch = batch.documents,
            codec = codec
        )
    }

    internal suspend fun getMore(
        context: MongoOperationContext,
        database: String,
        collection: String,
        cursorId: Long
    ): MongoCursorBatch {
        val result =
            context.runCommand(
                BsonDocument(
                    "getMore" to BsonInt64(cursorId),
                    "collection" to BsonString(collection),
                    "\$db" to BsonString(database)
                )
        )
        return result.raw.cursorBatch("nextBatch")
    }

    internal suspend fun killCursors(
        context: MongoOperationContext,
        database: String,
        collection: String,
        cursorId: Long
    ) {
        context.runCommand(
            BsonDocument(
                "killCursors" to BsonString(collection),
                "cursors" to BsonArray(listOf(BsonInt64(cursorId))),
                "\$db" to BsonString(database)
            )
        )
    }

    internal suspend fun listCollectionNames(context: MongoOperationContext, database: String): List<String> {
        val result =
            context.runCommand(
                BsonDocument(
                    "listCollections" to BsonInt32(1),
                    "nameOnly" to BsonBoolean(true),
                    "\$db" to BsonString(database)
                )
            )
        val batch = result.raw.cursorBatch("firstBatch")
        val cursor =
            MongoCursor(
                context = context,
                database = database,
                collection = "\$cmd.listCollections",
                initialCursorId = batch.id,
                initialBatch = batch.documents,
                codec = BsonDocumentCodec
            )
        return cursor.toList().map { document ->
            document.getString("name") ?: error("MongoDB listCollections response document did not contain string name")
        }
    }

    internal suspend fun createCollection(
        context: MongoOperationContext,
        database: String,
        collection: String
    ): MongoCommandResult {
        require(collection.isNotBlank()) { "MongoDB collection name cannot be blank" }
        return context.runCommand(
            BsonDocument(
                "create" to BsonString(collection),
                "\$db" to BsonString(database)
            )
        )
    }

    internal suspend fun dropCollection(
        context: MongoOperationContext,
        database: String,
        collection: String
    ): MongoCommandResult =
        context.runCommand(
            BsonDocument(
                "drop" to BsonString(collection),
                "\$db" to BsonString(database)
            )
        )

    internal suspend fun createIndex(
        context: MongoOperationContext,
        database: String,
        collection: String,
        keys: BsonDocument,
        name: String?,
        unique: Boolean
    ): String {
        require(!keys.isEmpty()) { "createIndex requires at least one key" }

        val indexName = name ?: keys.generatedIndexName()
        val index = linkedMapOf<String, BsonValue>()
        index["key"] = keys
        index["name"] = BsonString(indexName)
        if (unique) {
            index["unique"] = BsonBoolean(true)
        }

        context.runCommand(
            BsonDocument(
                "createIndexes" to BsonString(collection),
                "indexes" to BsonArray(listOf(BsonDocument(index))),
                "\$db" to BsonString(database)
            )
        )
        return indexName
    }

    internal suspend fun listIndexNames(
        context: MongoOperationContext,
        database: String,
        collection: String
    ): List<String> {
        val result =
            context.runCommand(
                BsonDocument(
                    "listIndexes" to BsonString(collection),
                    "\$db" to BsonString(database)
                )
            )
        val batch = result.raw.cursorBatch("firstBatch")
        val cursor =
            MongoCursor(
                context = context,
                database = database,
                collection = collection,
                initialCursorId = batch.id,
                initialBatch = batch.documents,
                codec = BsonDocumentCodec
            )
        return cursor.toList().map { document ->
            document.getString("name") ?: error("MongoDB listIndexes response document did not contain string name")
        }
    }

    internal suspend fun dropIndex(
        context: MongoOperationContext,
        database: String,
        collection: String,
        name: String
    ): MongoCommandResult {
        require(name.isNotBlank()) { "MongoDB index name cannot be blank" }
        return context.runCommand(
            BsonDocument(
                "dropIndexes" to BsonString(collection),
                "index" to BsonString(name),
                "\$db" to BsonString(database)
            )
        )
    }

    internal suspend fun findOne(
        context: MongoOperationContext,
        database: String,
        collection: String,
        filter: BsonDocument
    ): BsonDocument? {
        val result =
            context.runCommand(
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

internal interface MongoOperationContext {
    val client: MongoClient

    suspend fun runCommand(command: BsonDocument): MongoCommandResult
}

private class MongoClientOperationContext(
    override val client: MongoClient
) : MongoOperationContext {
    override suspend fun runCommand(command: BsonDocument): MongoCommandResult =
        client.sendCommand(command)
}

private class MongoSessionOperationContext(
    private val session: MongoSession
) : MongoOperationContext {
    override val client: MongoClient
        get() = session.client

    override suspend fun runCommand(command: BsonDocument): MongoCommandResult {
        session.ensureOpen()
        session.ensureNoActiveTransaction()
        return client.sendCommand(command.withAppendedFields("lsid" to session.sessionId))
    }
}

private class MongoTransactionOperationContext(
    private val transaction: MongoTransaction
) : MongoOperationContext {
    override val client: MongoClient
        get() = transaction.session.client

    override suspend fun runCommand(command: BsonDocument): MongoCommandResult {
        val fields = transaction.commandFieldsForOperation()
        return client.sendCommand(command.withAppendedFields(fields))
    }
}

public class MongoSession internal constructor(
    internal val client: MongoClient,
    internal val sessionId: BsonDocument
) {
    private val operationContext = MongoSessionOperationContext(this)
    private var ended = false
    private var nextTransactionNumber = 0L
    private var activeTransaction: MongoTransaction? = null

    public fun database(name: String): MongoDatabase {
        require(name.isNotBlank()) { "MongoDB database name cannot be blank" }
        ensureOpen()
        ensureNoActiveTransaction()
        return MongoDatabase(context = operationContext, name = name)
    }

    public fun startTransaction(): MongoTransaction {
        ensureOpen()
        check(activeTransaction == null) { "Transaction already in progress" }

        val transaction = MongoTransaction(session = this, txnNumber = ++nextTransactionNumber)
        activeTransaction = transaction
        return transaction
    }

    public suspend fun <R> withTransaction(block: suspend MongoTransaction.() -> R): R {
        val transaction = startTransaction()
        val result =
            try {
                transaction.block()
            } catch (throwable: Throwable) {
                try {
                    transaction.abort()
                } catch (abortFailure: Throwable) {
                    throwable.addSuppressed(abortFailure)
                }
                throw throwable
            }

        transaction.commit()
        return result
    }

    public suspend fun close() {
        if (ended) {
            return
        }

        val transaction = activeTransaction
        if (transaction != null) {
            try {
                transaction.abort()
            } catch (_: Throwable) {
                completeTransaction(transaction)
            }
        }

        ended = true
        try {
            client.endSession(sessionId)
        } catch (_: Throwable) {
            // The server expires abandoned sessions on its own; close() is best-effort.
        }
    }

    internal fun ensureOpen() {
        check(!ended) { "MongoSession is closed" }
    }

    internal fun ensureNoActiveTransaction() {
        check(activeTransaction == null) {
            "MongoSession has an active transaction; use MongoTransaction.database()"
        }
    }

    internal fun ensureActive(transaction: MongoTransaction) {
        ensureOpen()
        check(activeTransaction === transaction) { "MongoTransaction is no longer active" }
    }

    internal fun completeTransaction(transaction: MongoTransaction) {
        if (activeTransaction === transaction) {
            activeTransaction = null
        }
    }
}

private enum class MongoTransactionState {
    Starting,
    InProgress,
    Committed,
    Aborted
}

public class MongoTransaction internal constructor(
    internal val session: MongoSession,
    internal val txnNumber: Long
) {
    private val operationContext = MongoTransactionOperationContext(this)
    private var state = MongoTransactionState.Starting

    public fun database(name: String): MongoDatabase {
        require(name.isNotBlank()) { "MongoDB database name cannot be blank" }
        ensureCanRunOperation()
        return MongoDatabase(context = operationContext, name = name)
    }

    public suspend fun commit() {
        when (state) {
            MongoTransactionState.Starting -> {
                state = MongoTransactionState.Committed
                session.completeTransaction(this)
            }
            MongoTransactionState.InProgress -> {
                try {
                    operationContext.runCommand(
                        BsonDocument(
                            "commitTransaction" to BsonInt32(1),
                            "\$db" to BsonString("admin")
                        )
                    )
                } finally {
                    state = MongoTransactionState.Committed
                    session.completeTransaction(this)
                }
            }
            MongoTransactionState.Committed -> error("Cannot call commitTransaction twice")
            MongoTransactionState.Aborted -> error("Cannot call commitTransaction after calling abortTransaction")
        }
    }

    public suspend fun abort() {
        when (state) {
            MongoTransactionState.Starting -> {
                state = MongoTransactionState.Aborted
                session.completeTransaction(this)
            }
            MongoTransactionState.InProgress -> {
                try {
                    operationContext.runCommand(
                        BsonDocument(
                            "abortTransaction" to BsonInt32(1),
                            "\$db" to BsonString("admin")
                        )
                    )
                } finally {
                    state = MongoTransactionState.Aborted
                    session.completeTransaction(this)
                }
            }
            MongoTransactionState.Committed -> error("Cannot call abortTransaction after calling commitTransaction")
            MongoTransactionState.Aborted -> error("Cannot call abortTransaction twice")
        }
    }

    internal fun commandFieldsForOperation(): List<Pair<String, BsonValue>> {
        ensureCanRunOperation()

        val startsTransaction = state == MongoTransactionState.Starting
        state = MongoTransactionState.InProgress

        val fields = mutableListOf<Pair<String, BsonValue>>(
            "lsid" to session.sessionId,
            "txnNumber" to BsonInt64(txnNumber)
        )
        if (startsTransaction) {
            fields.add("startTransaction" to BsonBoolean(true))
        }
        fields.add("autocommit" to BsonBoolean(false))
        return fields
    }

    private fun ensureCanRunOperation() {
        session.ensureActive(this)
        check(state == MongoTransactionState.Starting || state == MongoTransactionState.InProgress) {
            "MongoTransaction is complete"
        }
    }
}

public class MongoCursor<T : Any> internal constructor(
    private val context: MongoOperationContext,
    private val database: String,
    private val collection: String,
    initialCursorId: Long,
    initialBatch: List<BsonDocument>,
    private val codec: MongoCodec<T>
) {
    private var cursorId = initialCursorId
    private val batch = ArrayDeque(initialBatch)
    private var closed = false

    public suspend fun next(): T? {
        if (closed) {
            return null
        }

        while (true) {
            if (batch.isNotEmpty()) {
                return codec.decode(batch.removeFirst())
            }

            if (cursorId == 0L) {
                closed = true
                return null
            }

            val nextBatch =
                context.client.getMore(
                    context = context,
                    database = database,
                    collection = collection,
                    cursorId = cursorId
                )
            cursorId = nextBatch.id
            batch.addAll(nextBatch.documents)
        }
    }

    public suspend fun toList(): List<T> {
        val documents = mutableListOf<T>()
        try {
            while (true) {
                val document = next() ?: break
                documents.add(document)
            }
        } catch (throwable: Throwable) {
            try {
                close()
            } catch (closeFailure: Throwable) {
                throwable.addSuppressed(closeFailure)
            }
            throw throwable
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
            context.client.killCursors(
                context = context,
                database = database,
                collection = collection,
                cursorId = id
            )
        }
    }
}

public class MongoDatabase internal constructor(
    internal val context: MongoOperationContext,
    public val name: String
) {
    init {
        require(name.isNotBlank()) { "MongoDB database name cannot be blank" }
    }

    public fun collection(name: String): BsonCollection =
        collection(name = name, codec = BsonDocumentCodec)

    public fun <T : Any> collection(name: String, codec: MongoCodec<T>): MongoCollection<T> {
        require(name.isNotBlank()) { "MongoDB collection name cannot be blank" }
        return MongoCollection(database = this, name = name, codec = codec)
    }

    public fun <T : Any> collection(name: String, serializer: KSerializer<T>): MongoCollection<T> =
        collection(name = name, codec = KotlinxBsonCodec(serializer))

    public inline fun <reified T : Any> typedCollection(name: String): MongoCollection<T> =
        collection(name = name, serializer = serializer())

    public suspend fun listCollectionNames(): List<String> =
        context.client.listCollectionNames(context = context, database = name)

    public suspend fun createCollection(name: String): MongoCommandResult =
        context.client.createCollection(context = context, database = this.name, collection = name)
}

public class MongoCollection<T : Any> internal constructor(
    private val database: MongoDatabase,
    public val name: String,
    private val codec: MongoCodec<T>
) {
    init {
        require(name.isNotBlank()) { "MongoDB collection name cannot be blank" }
    }

    public suspend fun insertOne(value: T): InsertOneResult =
        database.context.client.insertOne(
            context = database.context,
            database = database.name,
            collection = name,
            document = codec.encode(value)
        )

    public suspend fun insertMany(values: List<T>, ordered: Boolean = true): InsertManyResult =
        database.context.client.insertMany(
            context = database.context,
            database = database.name,
            collection = name,
            documents = values.map(codec::encode),
            ordered = ordered
        )

    public suspend fun deleteOne(block: TypedBsonFilterBuilder<T>.() -> Unit): DeleteResult =
        deleteOne(buildFilter(block))

    public suspend fun deleteOne(filter: BsonDocument): DeleteResult =
        database.context.client.deleteOne(
            context = database.context,
            database = database.name,
            collection = name,
            filter = filter
        )

    public suspend fun deleteMany(
        ordered: Boolean = true,
        block: TypedBsonFilterBuilder<T>.() -> Unit
    ): DeleteResult = deleteMany(filter = buildFilter(block), ordered = ordered)

    public suspend fun deleteMany(filter: BsonDocument, ordered: Boolean = true): DeleteResult =
        database.context.client.deleteMany(
            context = database.context,
            database = database.name,
            collection = name,
            filter = filter,
            ordered = ordered
        )

    public suspend fun updateOne(
        filter: BsonDocument,
        update: BsonDocument,
        upsert: Boolean = false
    ): UpdateResult =
        database.context.client.updateOne(
            context = database.context,
            database = database.name,
            collection = name,
            filter = filter,
            update = update,
            upsert = upsert
        )

    public suspend fun updateOne(
        filter: BsonDocument,
        upsert: Boolean = false,
        update: BsonUpdateBuilder.() -> Unit
    ): UpdateResult = updateOne(filter = filter, update = mongongo.update(update), upsert = upsert)

    public suspend fun updateOne(
        upsert: Boolean = false,
        filter: TypedBsonFilterBuilder<T>.() -> Unit,
        update: TypedBsonUpdateBuilder<T>.() -> Unit
    ): UpdateResult = updateOne(filter = buildFilter(filter), update = buildUpdate(update), upsert = upsert)

    public suspend fun updateMany(
        filter: BsonDocument,
        update: BsonDocument,
        upsert: Boolean = false
    ): UpdateResult =
        database.context.client.updateMany(
            context = database.context,
            database = database.name,
            collection = name,
            filter = filter,
            update = update,
            upsert = upsert
        )

    public suspend fun updateMany(
        filter: BsonDocument,
        upsert: Boolean = false,
        update: BsonUpdateBuilder.() -> Unit
    ): UpdateResult = updateMany(filter = filter, update = mongongo.update(update), upsert = upsert)

    public suspend fun updateMany(
        upsert: Boolean = false,
        filter: TypedBsonFilterBuilder<T>.() -> Unit,
        update: TypedBsonUpdateBuilder<T>.() -> Unit
    ): UpdateResult = updateMany(filter = buildFilter(filter), update = buildUpdate(update), upsert = upsert)

    public suspend fun replaceOne(
        filter: BsonDocument,
        replacement: BsonDocument,
        upsert: Boolean = false
    ): UpdateResult =
        database.context.client.replaceOne(
            context = database.context,
            database = database.name,
            collection = name,
            filter = filter,
            replacement = replacement,
            upsert = upsert
        )

    public suspend fun replaceOne(
        filter: TypedBsonFilterBuilder<T>.() -> Unit,
        replacement: T,
        upsert: Boolean = false
    ): UpdateResult = replaceOne(filter = buildFilter(filter), replacement = codec.encode(replacement), upsert = upsert)

    public suspend fun findOne(block: TypedBsonFilterBuilder<T>.() -> Unit): T? =
        findOne(buildFilter(block))

    public suspend fun findOne(filter: BsonDocument = BsonDocument()): T? =
        database.context.client.findOne(
            context = database.context,
            database = database.name,
            collection = name,
            filter = filter
        )?.let(codec::decode)

    public suspend fun find(
        filter: BsonDocument = BsonDocument(),
        limit: Int = 0,
        batchSize: Int? = null
    ): MongoCursor<T> =
        database.context.client.find(
            context = database.context,
            database = database.name,
            collection = name,
            filter = filter,
            limit = limit,
            batchSize = batchSize,
            codec = codec
        )

    public suspend fun find(
        limit: Int = 0,
        batchSize: Int? = null,
        block: TypedBsonFilterBuilder<T>.() -> Unit
    ): MongoCursor<T> = find(filter = buildFilter(block), limit = limit, batchSize = batchSize)

    private fun buildFilter(block: TypedBsonFilterBuilder<T>.() -> Unit): BsonDocument =
        when (codec) {
            is KotlinxBsonCodec<*> -> TypedBsonFilterBuilder<T>(codec.serializer).apply(block).build()
            else -> propertyRejectingFilter(block)
        }

    private fun buildUpdate(block: TypedBsonUpdateBuilder<T>.() -> Unit): BsonDocument =
        when (codec) {
            is KotlinxBsonCodec<*> -> TypedBsonUpdateBuilder<T>(codec.serializer).apply(block).build()
            else -> propertyRejectingUpdate(block)
        }

    public suspend fun drop(): MongoCommandResult =
        database.context.client.dropCollection(context = database.context, database = database.name, collection = name)

    public suspend fun createIndex(
        keys: BsonDocument,
        name: String? = null,
        unique: Boolean = false
    ): String =
        database.context.client.createIndex(
            context = database.context,
            database = database.name,
            collection = this.name,
            keys = keys,
            name = name,
            unique = unique
        )

    public suspend fun listIndexNames(): List<String> =
        database.context.client.listIndexNames(context = database.context, database = database.name, collection = name)

    public suspend fun dropIndex(name: String): MongoCommandResult =
        database.context.client.dropIndex(
            context = database.context,
            database = database.name,
            collection = this.name,
            name = name
        )
}

public suspend fun BsonCollection.insertOne(block: BsonDocumentBuilder.() -> Unit): InsertOneResult =
    insertOne(bsonDocument(block))

private fun BsonDocument.withAppendedFields(vararg fields: Pair<String, BsonValue>): BsonDocument =
    withAppendedFields(fields.toList())

private fun BsonDocument.withAppendedFields(fields: List<Pair<String, BsonValue>>): BsonDocument {
    val copy = linkedMapOf<String, BsonValue>()
    copy.putAll(values)
    for ((name, value) in fields) {
        check(name !in copy) { "MongoDB command already contains $name" }
        copy[name] = value
    }
    return BsonDocument(copy)
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

internal fun BsonDocument.commandErrorMetadata(): MongoErrorMetadata =
    MongoErrorMetadata(
        code = intValue("code"),
        codeName = getString("codeName"),
        errmsg = getString("errmsg"),
        errorLabels = errorLabels()
    )

private fun BsonDocument.firstWriteErrorMetadata(): MongoErrorMetadata? {
    val writeErrors = getArray("writeErrors") ?: return null
    return writeErrors.values.firstOrNull().asDocumentOrNull()?.commandErrorMetadata()
}

private fun BsonDocument.writeConcernErrorMetadata(): MongoErrorMetadata? =
    getDocument("writeConcernError")?.commandErrorMetadata()

internal fun BsonDocument.errorLabels(): Set<String> =
    getArray("errorLabels")
        ?.values
        ?.mapNotNull { (it as? BsonString)?.value }
        ?.toSet()
        ?: emptySet()

private fun BsonDocument.intValue(name: String): Int? =
    getInt32(name) ?: getInt64(name)?.toInt() ?: getDouble(name)?.toInt()

private fun BsonDocument.longValue(name: String): Long? = getNumberAsLong(name)

private fun BsonDocument.documentValue(name: String): BsonDocument =
    getDocument(name) ?: error("MongoDB response field $name was not a document")

private fun BsonValue?.asDocumentOrNull(): BsonDocument? = this as? BsonDocument

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

private fun BsonDocument.generatedIndexName(): String =
    values.entries.joinToString("_") { (name, value) -> "${name}_${value.indexNameValue()}" }

private fun BsonValue.indexNameValue(): String =
    when (this) {
        is BsonDouble -> value.toInt().toString()
        is BsonInt32 -> value.toString()
        is BsonInt64 -> value.toInt().toString()
        is BsonString -> value.replace(' ', '_')
        else -> ""
    }

private fun requireUpdateOperatorDocument(update: BsonDocument, operation: String) {
    val firstField = update.values.keys.firstOrNull()
    require(firstField != null && firstField.startsWith("\$")) {
        "$operation only supports update operator documents"
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
