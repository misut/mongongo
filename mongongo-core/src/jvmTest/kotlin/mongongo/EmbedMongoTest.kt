package mongongo

import com.mongodb.ConnectionString
import com.mongodb.kotlin.client.MongoClient as JvmMongoClient
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
}
