package mongongo

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
}
