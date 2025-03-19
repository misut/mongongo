package mongongo

import com.mongodb.kotlin.client.MongoClient
import kotlin.test.Test

class EmbedMongoTest {
    private val shardedEmbedMongoCluster = ShardedEmbedMongoCluster()
    private val syncClient: MongoClient
        get() = MongoClient.create(shardedEmbedMongoCluster.connectionString)

    data class TestDocument(val name: String)

    @Test
    fun test() {
        val insertOneResult = syncClient
            .getDatabase("test")
            .getCollection<TestDocument>("test")
            .insertOne(TestDocument("test"))
        println(insertOneResult)
        assert(false)
    }
}
