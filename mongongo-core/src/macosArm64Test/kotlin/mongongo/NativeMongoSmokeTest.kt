package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv

class NativeMongoSmokeTest {
    @Test
    fun insertsAgainstFakeOpMsgServer() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                assertEquals(BsonString("native_books"), insert.body["insert"])
                assertEquals(BsonString("native_library"), insert.body["\$db"])
                val documents = insert.body["documents"] as BsonArray
                val document = documents.values.single() as BsonDocument
                assertTrue(document["_id"] is BsonObjectId)
                assertEquals(BsonString("Ada"), document["name"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("native_library")
                        .collection("native_books")
                        .insertOne(BsonDocument("name" to BsonString("Ada")))
                assertTrue(result.insertedId is BsonObjectId)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun pingsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            assertEquals(1.0, client.ping().ok)
        } finally {
            client.close()
        }
    }

    @Test
    fun insertsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            val result =
                client
                    .database("mongongo_native_smoke")
                    .collection("insert_one")
                    .insertOne(BsonDocument("name" to BsonString("native")))
            assertTrue(result.acknowledged)
            assertTrue(result.insertedId is BsonObjectId)
        } finally {
            client.close()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environment(name: String): String? = getenv(name)?.toKString()
