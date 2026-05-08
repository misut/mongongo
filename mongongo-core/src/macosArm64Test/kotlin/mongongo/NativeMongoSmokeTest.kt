package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import platform.posix.getenv

class NativeMongoSmokeTest {
    @Test
    fun findsAgainstFakeOpMsgServer() = runTest {
        val filter = BsonDocument("name" to BsonString("Ada"))
        val found =
            BsonDocument(
                "_id" to BsonString("native-id"),
                "name" to BsonString("Ada")
            )

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(BsonString("native_books"), find.body["find"])
                assertEquals(filter, find.body["filter"])
                assertEquals(BsonInt32(1), find.body["limit"])
                assertEquals(BsonBoolean(true), find.body["singleBatch"])
                assertEquals(BsonString("native_library"), find.body["\$db"])
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("native_library.native_books"),
                                "firstBatch" to BsonArray(listOf(found))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("native_library").collection("native_books").findOne(filter)
                assertEquals(found, result)
            } finally {
                client.close()
            }
        }
    }

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

    @Test
    fun insertsAndFindsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .collection("find_one_${Random.nextInt(0, Int.MAX_VALUE)}")
            val insertResult = collection.insertOne(BsonDocument("name" to BsonString("native")))
            val insertedId = assertIs<BsonObjectId>(insertResult.insertedId)
            val found = collection.findOne(BsonDocument("_id" to insertedId))
            assertEquals(insertedId, found?.get("_id"))
            assertEquals(BsonString("native"), found?.get("name"))
        } finally {
            client.close()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environment(name: String): String? = getenv(name)?.toKString()
