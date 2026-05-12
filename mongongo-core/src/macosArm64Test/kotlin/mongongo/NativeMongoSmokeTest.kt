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
    fun authenticatesAgainstFakeOpMsgServer() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                expectScramSha256Authentication(authSource = "admin")
                val ping = receive()
                assertEquals(BsonInt32(1), ping.body["ping"])
                assertEquals(BsonString("native_auth"), ping.body["\$db"])
                reply(ping, BsonDocument("ok" to BsonDouble(1.0)))
            }
        ) { baseUri ->
            val client =
                MongoClient.connect(
                    uri = authUri(baseUri, database = "native_auth", authSource = "admin"),
                    nonceGenerator = MongoNonceGenerator { "native-client-nonce" }
                )
            try {
                assertEquals(1.0, client.ping("native_auth").ok)
            } finally {
                client.close()
            }
        }
    }

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
    fun insertsManyAgainstFakeOpMsgServer() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                assertEquals(BsonString("native_books"), insert.body["insert"])
                assertEquals(BsonBoolean(false), insert.body["ordered"])
                assertEquals(BsonString("native_library"), insert.body["\$db"])
                val documents = insert.body["documents"] as BsonArray
                assertEquals(2, documents.values.size)
                val first = documents.values[0] as BsonDocument
                val second = documents.values[1] as BsonDocument
                assertTrue(first["_id"] is BsonObjectId)
                assertTrue(second["_id"] is BsonObjectId)
                assertEquals(BsonString("Ada"), first["name"])
                assertEquals(BsonString("Grace"), second["name"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(2)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("native_library")
                        .collection("native_books")
                        .insertMany(
                            listOf(
                                BsonDocument("name" to BsonString("Ada")),
                                BsonDocument("name" to BsonString("Grace"))
                            ),
                            ordered = false
                        )
                assertEquals(2, result.insertedIds.size)
                assertTrue(result.insertedIds[0] is BsonObjectId)
                assertTrue(result.insertedIds[1] is BsonObjectId)
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
    fun deletesManyAgainstFakeOpMsgServer() = runTest {
        val filter = BsonDocument("status" to BsonString("archived"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                assertEquals(BsonString("native_books"), delete.body["delete"])
                assertEquals(BsonBoolean(false), delete.body["ordered"])
                assertEquals(BsonString("native_library"), delete.body["\$db"])
                val deletes = delete.body["deletes"] as BsonArray
                val statement = deletes.values.single() as BsonDocument
                assertEquals(filter, statement["q"])
                assertEquals(BsonInt32(0), statement["limit"])
                reply(delete, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(2)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("native_library")
                        .collection("native_books")
                        .deleteMany(filter, ordered = false)
                assertEquals(2L, result.deletedCount)
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
    fun updatesManyAgainstFakeOpMsgServer() = runTest {
        val filter = BsonDocument("status" to BsonString("draft"))
        val update = BsonDocument("\$set" to BsonDocument("status" to BsonString("reviewed")))

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
                assertEquals(BsonBoolean(true), statement["multi"])
                assertEquals(BsonBoolean(true), statement["upsert"])
                reply(
                    updateCommand,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(2),
                        "nModified" to BsonInt32(2)
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
                        .updateMany(filter = filter, update = update, upsert = true)
                assertEquals(2L, result.matchedCount)
                assertEquals(2L, result.modifiedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun replacesAgainstFakeOpMsgServer() = runTest {
        val filter = BsonDocument("name" to BsonString("Ada"))
        val replacement =
            BsonDocument(
                "name" to BsonString("Grace"),
                "role" to BsonString("admin")
            )

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
                assertEquals(replacement, statement["u"])
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
                        .replaceOne(filter = filter, replacement = replacement)
                assertEquals(1L, result.matchedCount)
                assertEquals(1L, result.modifiedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun authenticatesConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_AUTH_TEST_URI") ?: return@runTest
        smokeConfiguredMongoUri(uri, marker = "native-auth")
    }

    @Test
    fun smokesConfiguredTlsMongoUri() = runTest {
        val uri = environment("MONGONGO_TLS_TEST_URI") ?: return@runTest
        smokeConfiguredMongoUri(uri, marker = "native-tls")
    }

    @Test
    fun smokesConfiguredAuthTlsMongoUri() = runTest {
        val uri = environment("MONGONGO_AUTH_TLS_TEST_URI") ?: return@runTest
        smokeConfiguredMongoUri(uri, marker = "native-auth-tls")
    }

    @Test
    fun smokesConfiguredSrvMongoUri() = runTest {
        val uri = environment("MONGONGO_SRV_TEST_URI") ?: return@runTest
        smokeConfiguredMongoUri(uri, marker = "native-srv")
    }

    @Test
    fun smokesConfiguredAuthSrvMongoUri() = runTest {
        val uri = environment("MONGONGO_AUTH_SRV_TEST_URI") ?: return@runTest
        smokeConfiguredMongoUri(uri, marker = "native-auth-srv")
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
    fun insertsManyAndFindsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val expectedNames = setOf("native-insert-many-a", "native-insert-many-b", "native-insert-many-c")
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .collection("insert_many_${Random.nextInt(0, Int.MAX_VALUE)}")
            val result =
                collection.insertMany(
                    expectedNames.map { name -> BsonDocument("name" to BsonString(name)) },
                    ordered = false
                )
            assertEquals(3, result.insertedIds.size)

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
    fun insertsUpdatesManyAndFindsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .collection("update_many_${Random.nextInt(0, Int.MAX_VALUE)}")
            collection.insertMany(
                listOf(
                    BsonDocument("name" to BsonString("native-a"), "status" to BsonString("draft")),
                    BsonDocument("name" to BsonString("native-b"), "status" to BsonString("draft")),
                    BsonDocument("name" to BsonString("native-c"), "status" to BsonString("published"))
                )
            )
            val updateResult =
                collection.updateMany(
                    filter = BsonDocument("status" to BsonString("draft")),
                    update = BsonDocument("\$set" to BsonDocument("status" to BsonString("reviewed")))
                )
            assertEquals(2L, updateResult.matchedCount)
            assertEquals(2L, updateResult.modifiedCount)
            val found = collection.find().toList()
            assertEquals(2, found.count { it.stringValue("status") == "reviewed" })
            assertEquals(1, found.count { it.stringValue("status") == "published" })
        } finally {
            client.close()
        }
    }

    @Test
    fun insertsReplacesAndFindsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .collection("replace_one_${Random.nextInt(0, Int.MAX_VALUE)}")
            val insertResult =
                collection.insertOne(
                    BsonDocument(
                        "name" to BsonString("native"),
                        "role" to BsonString("reader")
                    )
                )
            val insertedId = assertIs<BsonObjectId>(insertResult.insertedId)
            val replaceResult =
                collection.replaceOne(
                    filter = BsonDocument("_id" to insertedId),
                    replacement =
                        BsonDocument(
                            "_id" to insertedId,
                            "name" to BsonString("native-replaced")
                        )
                )
            assertEquals(1L, replaceResult.matchedCount)
            assertEquals(1L, replaceResult.modifiedCount)
            val found = collection.findOne(BsonDocument("_id" to insertedId))
            assertEquals(BsonString("native-replaced"), found?.get("name"))
            assertNull(found?.get("role"))
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

    @Test
    fun insertsDeletesManyAndFindsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .collection("delete_many_${Random.nextInt(0, Int.MAX_VALUE)}")
            collection.insertMany(
                listOf(
                    BsonDocument("name" to BsonString("native-a"), "status" to BsonString("remove")),
                    BsonDocument("name" to BsonString("native-b"), "status" to BsonString("remove")),
                    BsonDocument("name" to BsonString("native-c"), "status" to BsonString("keep"))
                )
            )
            val deleteResult = collection.deleteMany(BsonDocument("status" to BsonString("remove")), ordered = false)
            assertEquals(2L, deleteResult.deletedCount)
            val found = collection.find().toList()
            assertEquals(listOf("native-c"), found.map { it.stringValue("name") })
        } finally {
            client.close()
        }
    }

    private suspend fun smokeConfiguredMongoUri(uri: String, marker: String) {
        val databaseName = MongoConnectionStringParser.parse(uri).database ?: "test"
        val client = MongoClient.connect(uri)
        try {
            assertEquals(1.0, client.ping(databaseName).ok)
            val collection =
                client
                    .database(databaseName)
                    .collection("smoke_${marker.replace('-', '_')}_${Random.nextInt(0, Int.MAX_VALUE)}")
            val insertResult = collection.insertOne(BsonDocument("name" to BsonString(marker)))
            val insertedId = assertIs<BsonObjectId>(insertResult.insertedId)
            val found = collection.findOne(BsonDocument("_id" to insertedId))
            assertEquals(BsonString(marker), found?.get("name"))
        } finally {
            client.close()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environment(name: String): String? = getenv(name)?.toKString()

private fun BsonDocument.stringValue(name: String): String =
    (this[name] as? BsonString)?.value ?: error("Expected BSON string field $name")
