package mongongo

import com.mongodb.ConnectionString
import com.mongodb.kotlin.client.MongoClient as JvmMongoClient
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bson.Document
import org.bson.types.ObjectId

class EmbedMongoTest {
    private val shardedEmbedMongoCluster = ShardedEmbedMongoCluster()
    private val syncClient
        get() = JvmMongoClient.create(shardedEmbedMongoCluster.connectionString)

    data class TestDocument(val name: String)

    @Test
    fun authenticatesConfiguredMongoUriWithJvmDriverAndMongongoClient() = runTest {
        val uri = System.getenv("MONGONGO_AUTH_TEST_URI") ?: return@runTest
        val databaseName = ConnectionString(uri).database ?: "test"
        val driverCollectionName = "jvm_auth_smoke_${Random.nextInt(0, Int.MAX_VALUE)}"
        val mongongoCollectionName = "mongongo_auth_smoke_${Random.nextInt(0, Int.MAX_VALUE)}"

        JvmMongoClient.create(uri).use { client ->
            val database = client.getDatabase(databaseName)
            assertEquals(1.0, database.runCommand(Document("ping", 1)).getDouble("ok"))

            val id = ObjectId()
            val collection = database.getCollection<Document>(driverCollectionName)
            collection.insertOne(Document("_id", id).append("name", "jvm"))
            assertEquals("jvm", collection.find(Document("_id", id)).first().getString("name"))
        }

        val client = MongoClient.connect(uri)
        try {
            assertEquals(1.0, client.ping(databaseName).ok)
            val collection = client.database(databaseName).collection(mongongoCollectionName)
            val insertResult = collection.insertOne(BsonDocument("name" to BsonString("mongongo")))
            val insertedId = assertIs<BsonObjectId>(insertResult.insertedId)
            val found = collection.findOne(BsonDocument("_id" to insertedId))
            assertEquals(BsonString("mongongo"), found?.get("name"))
        } finally {
            client.close()
        }
    }

    @Test
    fun pingEmbeddedMongoWithJvmDriverAndMongongoClient() = runTest {
        syncClient.use { client ->
            val result = client.getDatabase("admin").runCommand(Document("ping", 1))
            assertEquals(1.0, result.getDouble("ok"))
        }

        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            assertEquals(1.0, client.ping().ok)
        } finally {
            client.close()
        }
    }

    @Test
    fun createsListsAndDropsCollectionWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "database_commands_${Random.nextInt(0, Int.MAX_VALUE)}"
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val database = client.database(databaseName)
            assertEquals(1.0, database.createCollection(collectionName).ok)
            assertTrue(collectionName in database.listCollectionNames())

            syncClient.use { verifier ->
                val names = verifier.getDatabase(databaseName).listCollectionNames().toList()
                assertTrue(collectionName in names)
            }

            assertEquals(1.0, database.collection(collectionName).drop().ok)
            assertTrue(collectionName !in database.listCollectionNames())
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val names = verifier.getDatabase(databaseName).listCollectionNames().toList()
            assertTrue(collectionName !in names)
        }
    }

    @Test
    fun createsListsAndDropsIndexWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "index_commands_${Random.nextInt(0, Int.MAX_VALUE)}"
        val indexName = "name_1_age_-1"
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val createdName =
                collection.createIndex(
                    BsonDocument(
                        "name" to BsonInt32(1),
                        "age" to BsonInt32(-1)
                    )
                )
            assertEquals(indexName, createdName)
            assertTrue(indexName in collection.listIndexNames())

            syncClient.use { verifier ->
                val names =
                    verifier
                        .getDatabase(databaseName)
                        .getCollection<Document>(collectionName)
                        .listIndexes()
                        .map { it.getString("name") }
                        .toList()
                assertTrue(indexName in names)
            }

            assertEquals(1.0, collection.dropIndex(indexName).ok)
            assertTrue(indexName !in collection.listIndexNames())
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val names =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .listIndexes()
                    .map { it.getString("name") }
                    .toList()
            assertTrue(indexName !in names)
        }
    }

    @Test
    fun usesSessionForCrudWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "session_crud_${Random.nextInt(0, Int.MAX_VALUE)}"
        var insertedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            client.withSession {
                val collection = database(databaseName).collection(collectionName)
                val insertResult = collection.insertOne(BsonDocument("name" to BsonString("session")))
                val id = assertIs<BsonObjectId>(insertResult.insertedId)
                insertedId = id

                val found = collection.findOne(BsonDocument("_id" to id))
                assertEquals(BsonString("session"), found?.get("name"))
            }
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val id = insertedId ?: error("Session smoke did not insert a document")
            val stored =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .find(Document("_id", ObjectId(id.bytes.toByteArray())))
                    .first()
            assertEquals("session", stored.getString("name"))
        }
    }

    @Test
    fun commitsAndAbortsTransactionsWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val committedCollectionName = "transaction_commit_${Random.nextInt(0, Int.MAX_VALUE)}"
        val abortedCollectionName = "transaction_abort_${Random.nextInt(0, Int.MAX_VALUE)}"
        var committedId: BsonObjectId? = null
        var abortedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            try {
                client.withTransaction {
                    val collection = database(databaseName).collection(committedCollectionName)
                    val insertResult = collection.insertOne(BsonDocument("name" to BsonString("committed")))
                    val id = assertIs<BsonObjectId>(insertResult.insertedId)
                    committedId = id

                    val found = collection.findOne(BsonDocument("_id" to id))
                    assertEquals(BsonString("committed"), found?.get("name"))
                }

                val failure =
                    assertFailsWith<IllegalStateException> {
                        client.withTransaction {
                            val collection = database(databaseName).collection(abortedCollectionName)
                            val insertResult = collection.insertOne(BsonDocument("name" to BsonString("aborted")))
                            abortedId = assertIs<BsonObjectId>(insertResult.insertedId)
                            error("rollback")
                        }
                    }
                assertEquals("rollback", failure.message)
            } catch (exception: MongoCommandException) {
                if (exception.isEmbeddedTransactionSupportFailure()) {
                    return@runTest
                }
                throw exception
            }
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val committedObjectId = committedId ?: error("Transaction commit smoke did not insert a document")
            val abortedObjectId = abortedId ?: error("Transaction abort smoke did not insert a document")
            val database = verifier.getDatabase(databaseName)
            val committed =
                database
                    .getCollection<Document>(committedCollectionName)
                    .find(Document("_id", ObjectId(committedObjectId.bytes.toByteArray())))
                    .first()
            assertEquals("committed", committed.getString("name"))

            val abortedCount =
                database
                    .getCollection<Document>(abortedCollectionName)
                    .countDocuments(Document("_id", ObjectId(abortedObjectId.bytes.toByteArray())))
            assertEquals(0L, abortedCount)
        }
    }

    @Test
    fun insertsDocumentWithMongongoClientAndReadsItWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "insert_one_${Random.nextInt(0, Int.MAX_VALUE)}"
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        val insertedId =
            try {
                val result =
                    client
                        .database(databaseName)
                        .collection(collectionName)
                        .insertOne(BsonDocument("name" to BsonString("Ada")))
                assertIs<BsonObjectId>(result.insertedId)
            } finally {
                client.close()
            }

        syncClient.use { verifier ->
            val stored =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .find(Document("_id", ObjectId(insertedId.bytes.toByteArray())))
                    .first()
            assertEquals("Ada", stored.getString("name"))
        }
    }

    @Test
    fun insertsManyDocumentsWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "insert_many_${Random.nextInt(0, Int.MAX_VALUE)}"
        val expectedNames = setOf("Ada", "Grace", "Linus")
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val result =
                collection.insertMany(
                    listOf(
                        BsonDocument("name" to BsonString("Ada")),
                        BsonDocument("name" to BsonString("Grace")),
                        BsonDocument("name" to BsonString("Linus"))
                    )
                )
            assertEquals(3, result.insertedIds.size)
            assertIs<BsonObjectId>(result.insertedIds[0])
            assertIs<BsonObjectId>(result.insertedIds[1])
            assertIs<BsonObjectId>(result.insertedIds[2])

            val found = collection.find().toList()
            assertEquals(expectedNames, found.map { it.stringValue("name") }.toSet())
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val collection = verifier.getDatabase(databaseName).getCollection<Document>(collectionName)
            assertEquals(3L, collection.countDocuments(Document()))
            assertEquals(expectedNames, collection.find().toList().map { it.getString("name") }.toSet())
        }
    }

    @Test
    fun findsDocumentInsertedWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "find_one_${Random.nextInt(0, Int.MAX_VALUE)}"
        var insertedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val insertResult =
                collection.insertOne(
                    BsonDocument(
                        "name" to BsonString("Ada"),
                        "role" to BsonString("reader")
                    )
                )
            val id = assertIs<BsonObjectId>(insertResult.insertedId)
            insertedId = id

            val found = collection.findOne(BsonDocument("_id" to id))
            assertEquals(id, found?.get("_id"))
            assertEquals(BsonString("Ada"), found?.get("name"))
            assertEquals(BsonString("reader"), found?.get("role"))
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val stored =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .find(Document("_id", ObjectId(insertedId.bytes.toByteArray())))
                    .first()
            assertEquals("Ada", stored.getString("name"))
            assertEquals("reader", stored.getString("role"))
        }
    }

    @Test
    fun typedCollectionInsertsAndFindsAgainstEmbeddedMongo() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "typed_collection_${Random.nextInt(0, Int.MAX_VALUE)}"
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        val insertedId =
            try {
                val collection = client.database(databaseName).collection(collectionName, TestBookCodec)
                val insertResult = collection.insertOne(TestBook("The Fifth Season"))
                val id = assertIs<BsonObjectId>(insertResult.insertedId)

                val found = collection.findOne(BsonDocument("_id" to id))
                assertEquals(TestBook("The Fifth Season"), found)
                id
            } finally {
                client.close()
            }

        syncClient.use { verifier ->
            val stored =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .find(Document("_id", ObjectId(insertedId.bytes.toByteArray())))
                    .first()
            assertEquals("The Fifth Season", stored.getString("title"))
        }
    }

    @Test
    fun deletesDocumentWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "delete_one_${Random.nextInt(0, Int.MAX_VALUE)}"
        var insertedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val insertResult = collection.insertOne(BsonDocument("name" to BsonString("Ada")))
            val id = assertIs<BsonObjectId>(insertResult.insertedId)
            insertedId = id

            val deleteResult = collection.deleteOne(BsonDocument("_id" to id))
            assertEquals(1L, deleteResult.deletedCount)
            assertNull(collection.findOne(BsonDocument("_id" to id)))
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val remaining =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .countDocuments(Document("_id", ObjectId(insertedId.bytes.toByteArray())))
            assertEquals(0L, remaining)
        }
    }

    @Test
    fun deletesManyDocumentsWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "delete_many_${Random.nextInt(0, Int.MAX_VALUE)}"
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            collection.insertMany(
                listOf(
                    BsonDocument("name" to BsonString("Ada"), "status" to BsonString("remove")),
                    BsonDocument("name" to BsonString("Grace"), "status" to BsonString("remove")),
                    BsonDocument("name" to BsonString("Linus"), "status" to BsonString("keep"))
                )
            )

            val deleteResult = collection.deleteMany(BsonDocument("status" to BsonString("remove")), ordered = false)
            assertEquals(2L, deleteResult.deletedCount)

            val found = collection.find().toList()
            assertEquals(listOf("Linus"), found.map { it.stringValue("name") })
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val collection = verifier.getDatabase(databaseName).getCollection<Document>(collectionName)
            assertEquals(1L, collection.countDocuments(Document()))
            assertEquals(0L, collection.countDocuments(Document("status", "remove")))
            assertEquals(1L, collection.countDocuments(Document("status", "keep")))
        }
    }

    @Test
    fun updatesDocumentWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "update_one_${Random.nextInt(0, Int.MAX_VALUE)}"
        var insertedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val insertResult =
                collection.insertOne(
                    BsonDocument(
                        "name" to BsonString("Ada"),
                        "role" to BsonString("reader")
                    )
                )
            val id = assertIs<BsonObjectId>(insertResult.insertedId)
            insertedId = id

            val updateResult =
                collection.updateOne(
                    filter = BsonDocument("_id" to id),
                    update = BsonDocument("\$set" to BsonDocument("role" to BsonString("writer")))
                )
            assertEquals(1L, updateResult.matchedCount)
            assertEquals(1L, updateResult.modifiedCount)

            val found = collection.findOne(BsonDocument("_id" to id))
            assertEquals(BsonString("Ada"), found?.get("name"))
            assertEquals(BsonString("writer"), found?.get("role"))
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val stored =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .find(Document("_id", ObjectId(insertedId.bytes.toByteArray())))
                    .first()
            assertEquals("Ada", stored.getString("name"))
            assertEquals("writer", stored.getString("role"))
        }
    }

    @Test
    fun updatesManyDocumentsWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "update_many_${Random.nextInt(0, Int.MAX_VALUE)}"
        var matchedCount = 0L
        var modifiedCount = 0L
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            collection.insertMany(
                listOf(
                    BsonDocument("name" to BsonString("Ada"), "status" to BsonString("draft")),
                    BsonDocument("name" to BsonString("Grace"), "status" to BsonString("draft")),
                    BsonDocument("name" to BsonString("Linus"), "status" to BsonString("published"))
                )
            )

            val updateResult =
                collection.updateMany(
                    filter = BsonDocument("status" to BsonString("draft")),
                    update = BsonDocument("\$set" to BsonDocument("status" to BsonString("reviewed")))
                )
            matchedCount = updateResult.matchedCount
            modifiedCount = updateResult.modifiedCount
            assertEquals(2L, matchedCount)
            assertEquals(2L, modifiedCount)

            val found = collection.find().toList()
            assertEquals(2, found.count { it.stringValue("status") == "reviewed" })
            assertEquals(1, found.count { it.stringValue("status") == "published" })
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val collection = verifier.getDatabase(databaseName).getCollection<Document>(collectionName)
            assertEquals(matchedCount, collection.countDocuments(Document("status", "reviewed")))
            assertEquals(modifiedCount, collection.countDocuments(Document("status", "reviewed")))
            assertEquals(1L, collection.countDocuments(Document("status", "published")))
        }
    }

    @Test
    fun findsDocumentsInsertedWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "find_many_${Random.nextInt(0, Int.MAX_VALUE)}"
        val expectedNames = setOf("Ada", "Grace", "Linus")
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            for (name in expectedNames) {
                collection.insertOne(BsonDocument("name" to BsonString(name)))
            }

            val found = collection.find().toList()
            assertEquals(expectedNames, found.map { it.stringValue("name") }.toSet())
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val collection = verifier.getDatabase(databaseName).getCollection<Document>(collectionName)
            assertEquals(3L, collection.countDocuments(Document()))
            assertEquals(expectedNames, collection.find().toList().map { it.getString("name") }.toSet())
        }
    }

    @Test
    fun findHonorsLimitAgainstEmbeddedMongo() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "find_many_limit_${Random.nextInt(0, Int.MAX_VALUE)}"
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            collection.insertOne(BsonDocument("name" to BsonString("Ada")))
            collection.insertOne(BsonDocument("name" to BsonString("Grace")))
            collection.insertOne(BsonDocument("name" to BsonString("Linus")))

            val found = collection.find(limit = 2).toList()
            assertEquals(2, found.size)
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val collection = verifier.getDatabase(databaseName).getCollection<Document>(collectionName)
            assertEquals(3L, collection.countDocuments(Document()))
            assertEquals(2, collection.find().limit(2).toList().size)
        }
    }

    @Test
    fun upsertsDocumentWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "update_one_upsert_${Random.nextInt(0, Int.MAX_VALUE)}"
        var upsertedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val updateResult =
                collection.updateOne(
                    filter = BsonDocument("name" to BsonString("Grace")),
                    update = BsonDocument("\$set" to BsonDocument("role" to BsonString("admin"))),
                    upsert = true
                )
            val id = assertIs<BsonObjectId>(updateResult.upsertedId)
            upsertedId = id
            assertEquals(1L, updateResult.matchedCount)
            assertEquals(0L, updateResult.modifiedCount)

            val found = collection.findOne(BsonDocument("_id" to id))
            assertEquals(BsonString("Grace"), found?.get("name"))
            assertEquals(BsonString("admin"), found?.get("role"))
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val stored =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .find(Document("_id", ObjectId(upsertedId.bytes.toByteArray())))
                    .first()
            assertEquals("Grace", stored.getString("name"))
            assertEquals("admin", stored.getString("role"))
        }
    }

    @Test
    fun upsertsWithUpdateManyAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "update_many_upsert_${Random.nextInt(0, Int.MAX_VALUE)}"
        var upsertedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val updateResult =
                collection.updateMany(
                    filter = BsonDocument("name" to BsonString("Batch")),
                    update = BsonDocument("\$set" to BsonDocument("role" to BsonString("created"))),
                    upsert = true
                )
            val id = assertIs<BsonObjectId>(updateResult.upsertedId)
            upsertedId = id
            assertEquals(1L, updateResult.matchedCount)
            assertEquals(0L, updateResult.modifiedCount)

            val found = collection.findOne(BsonDocument("_id" to id))
            assertEquals(BsonString("Batch"), found?.get("name"))
            assertEquals(BsonString("created"), found?.get("role"))
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val collection = verifier.getDatabase(databaseName).getCollection<Document>(collectionName)
            assertEquals(1L, collection.countDocuments(Document("name", "Batch")))
            val stored = collection.find(Document("_id", ObjectId(upsertedId.bytes.toByteArray()))).first()
            assertEquals("Batch", stored.getString("name"))
            assertEquals("created", stored.getString("role"))
        }
    }

    @Test
    fun replacesDocumentWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "replace_one_${Random.nextInt(0, Int.MAX_VALUE)}"
        var insertedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val insertResult =
                collection.insertOne(
                    BsonDocument(
                        "name" to BsonString("Ada"),
                        "role" to BsonString("reader")
                    )
                )
            val id = assertIs<BsonObjectId>(insertResult.insertedId)
            insertedId = id

            val replaceResult =
                collection.replaceOne(
                    filter = BsonDocument("_id" to id),
                    replacement =
                        BsonDocument(
                            "_id" to id,
                            "name" to BsonString("Grace")
                        )
                )
            assertEquals(1L, replaceResult.matchedCount)
            assertEquals(1L, replaceResult.modifiedCount)

            val found = collection.findOne(BsonDocument("_id" to id))
            assertEquals(id, found?.get("_id"))
            assertEquals(BsonString("Grace"), found?.get("name"))
            assertNull(found?.get("role"))
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val stored =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .find(Document("_id", ObjectId(insertedId.bytes.toByteArray())))
                    .first()
            assertEquals("Grace", stored.getString("name"))
            assertNull(stored.getString("role"))
        }
    }

    @Test
    fun upsertsReplacementWithMongongoClientAndVerifiesWithJvmDriver() = runTest {
        val databaseName = "mongongo_test"
        val collectionName = "replace_one_upsert_${Random.nextInt(0, Int.MAX_VALUE)}"
        var upsertedId: BsonObjectId? = null
        val client = MongoClient.connect(shardedEmbedMongoCluster.connectionString.connectionString)
        try {
            val collection = client.database(databaseName).collection(collectionName)
            val replaceResult =
                collection.replaceOne(
                    filter = BsonDocument("name" to BsonString("Linus")),
                    replacement =
                        BsonDocument(
                            "name" to BsonString("Linus"),
                            "role" to BsonString("maintainer")
                        ),
                    upsert = true
                )
            val id = assertIs<BsonObjectId>(replaceResult.upsertedId)
            upsertedId = id
            assertEquals(1L, replaceResult.matchedCount)
            assertEquals(0L, replaceResult.modifiedCount)

            val found = collection.findOne(BsonDocument("_id" to id))
            assertEquals(BsonString("Linus"), found?.get("name"))
            assertEquals(BsonString("maintainer"), found?.get("role"))
        } finally {
            client.close()
        }

        syncClient.use { verifier ->
            val stored =
                verifier
                    .getDatabase(databaseName)
                    .getCollection<Document>(collectionName)
                    .find(Document("_id", ObjectId(upsertedId.bytes.toByteArray())))
                    .first()
            assertEquals("Linus", stored.getString("name"))
            assertEquals("maintainer", stored.getString("role"))
        }
    }

    private fun BsonDocument.stringValue(name: String): String =
        (this[name] as? BsonString)?.value ?: error("Expected BSON string field $name")

    private fun MongoCommandException.isEmbeddedTransactionSupportFailure(): Boolean {
        val codeName = result.getString("codeName").orEmpty()
        val message = result.getString("errmsg").orEmpty()
        return listOf(codeName, message).any { value ->
            value.contains("Transaction", ignoreCase = true) &&
                (
                    value.contains("replica", ignoreCase = true) ||
                        value.contains("support", ignoreCase = true) ||
                        value.contains("shard", ignoreCase = true)
                )
        }
    }
}
