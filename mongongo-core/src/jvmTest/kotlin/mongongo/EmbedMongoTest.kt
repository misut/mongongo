package mongongo

import com.mongodb.kotlin.client.MongoClient as JvmMongoClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bson.Document

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
}
