package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    fun findsManyAgainstFakeOpMsgServer() = runTest {
        val filter = BsonDocument("kind" to BsonString("language"))
        val first = BsonDocument("_id" to BsonString("native-first"), "name" to BsonString("Kotlin"))
        val second = BsonDocument("_id" to BsonString("native-second"), "name" to BsonString("Swift"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(BsonString("native_books"), find.body["find"])
                assertEquals(filter, find.body["filter"])
                assertEquals(BsonInt32(1), find.body["batchSize"])
                assertEquals(BsonString("native_library"), find.body["\$db"])
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(321),
                                "ns" to BsonString("native_library.native_books"),
                                "firstBatch" to BsonArray(listOf(first))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val getMore = receive()
                assertEquals(BsonInt64(321), getMore.body["getMore"])
                assertEquals(BsonString("native_books"), getMore.body["collection"])
                assertEquals(BsonString("native_library"), getMore.body["\$db"])
                reply(
                    getMore,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("native_library.native_books"),
                                "nextBatch" to BsonArray(listOf(second))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("native_library")
                        .collection("native_books")
                        .find(filter = filter, batchSize = 1)
                        .toList()
                assertEquals(listOf(first, second), result)
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
    fun deletesAgainstFakeOpMsgServer() = runTest {
        val filter = BsonDocument("name" to BsonString("Ada"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                assertEquals(BsonString("native_books"), delete.body["delete"])
                assertEquals(BsonBoolean(true), delete.body["ordered"])
                assertEquals(BsonString("native_library"), delete.body["\$db"])
                val deletes = delete.body["deletes"] as BsonArray
                val statement = deletes.values.single() as BsonDocument
                assertEquals(filter, statement["q"])
                assertEquals(BsonInt32(1), statement["limit"])
                reply(delete, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("native_library").collection("native_books").deleteOne(filter)
                assertEquals(1L, result.deletedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updatesAgainstFakeOpMsgServer() = runTest {
        val filter = BsonDocument("name" to BsonString("Ada"))
        val update = BsonDocument("\$set" to BsonDocument("role" to BsonString("writer")))

        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                assertEquals(BsonString("native_books"), updateCommand.body["update"])
                assertEquals(BsonBoolean(true), updateCommand.body["ordered"])
                assertEquals(BsonString("native_library"), updateCommand.body["\$db"])
                val updates = updateCommand.body["updates"] as BsonArray
                val statement = updates.values.single() as BsonDocument
                assertEquals(filter, statement["q"])
                assertEquals(update, statement["u"])
                assertEquals(BsonBoolean(false), statement["multi"])
                assertEquals(BsonBoolean(false), statement["upsert"])
                reply(
                    updateCommand,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(1),
                        "nModified" to BsonInt32(1)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("native_library")
                        .collection("native_books")
                        .updateOne(filter = filter, update = update)
                assertEquals(1L, result.matchedCount)
                assertEquals(1L, result.modifiedCount)
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

    @Test
    fun insertsAndFindsManyConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val expectedNames = setOf("native-a", "native-b", "native-c")
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .collection("find_many_${Random.nextInt(0, Int.MAX_VALUE)}")
            for (name in expectedNames) {
                collection.insertOne(BsonDocument("name" to BsonString(name)))
            }

            val found = collection.find().toList()
            assertEquals(expectedNames, found.map { it.stringValue("name") }.toSet())
        } finally {
            client.close()
        }
    }

    @Test
    fun insertsUpdatesAndFindsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .collection("update_one_${Random.nextInt(0, Int.MAX_VALUE)}")
            val insertResult =
                collection.insertOne(
                    BsonDocument(
                        "name" to BsonString("native"),
                        "role" to BsonString("reader")
                    )
                )
            val insertedId = assertIs<BsonObjectId>(insertResult.insertedId)
            val updateResult =
                collection.updateOne(
                    filter = BsonDocument("_id" to insertedId),
                    update = BsonDocument("\$set" to BsonDocument("role" to BsonString("writer")))
                )
            assertEquals(1L, updateResult.matchedCount)
            assertEquals(1L, updateResult.modifiedCount)
            val found = collection.findOne(BsonDocument("_id" to insertedId))
            assertEquals(BsonString("writer"), found?.get("role"))
        } finally {
            client.close()
        }
    }

    @Test
    fun insertsDeletesAndFindsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .collection("delete_one_${Random.nextInt(0, Int.MAX_VALUE)}")
            val insertResult = collection.insertOne(BsonDocument("name" to BsonString("native")))
            val insertedId = assertIs<BsonObjectId>(insertResult.insertedId)
            val deleteResult = collection.deleteOne(BsonDocument("_id" to insertedId))
            assertEquals(1L, deleteResult.deletedCount)
            assertNull(collection.findOne(BsonDocument("_id" to insertedId)))
        } finally {
            client.close()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environment(name: String): String? = getenv(name)?.toKString()

private fun BsonDocument.stringValue(name: String): String =
    (this[name] as? BsonString)?.value ?: error("Expected BSON string field $name")
