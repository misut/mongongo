package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import platform.posix.getenv

@Serializable
private data class NativeSerialNamedBook(
    @SerialName("_id")
    val id: BsonObjectId,
    @SerialName("book_title")
    val title: String,
    @SerialName("published_year")
    val year: Int = 0
)

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
    fun typedCollectionInsertsAndFindsAgainstFakeOpMsgServer() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()

                val insert = receive()
                assertEquals(BsonString("native_books"), insert.body["insert"])
                assertEquals(BsonString("native_library"), insert.body["\$db"])
                val document =
                    (insert.body["documents"] as BsonArray)
                        .values
                        .single() as BsonDocument
                val generatedId = document["_id"] as BsonObjectId
                assertEquals(BsonString("Native Typed"), document["title"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val find = receive()
                assertEquals(BsonString("native_books"), find.body["find"])
                assertEquals(BsonDocument("_id" to generatedId), find.body["filter"])
                assertEquals(BsonString("native_library"), find.body["\$db"])
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("native_library.native_books"),
                                "firstBatch" to
                                    BsonArray(
                                        listOf(
                                            BsonDocument(
                                                "_id" to generatedId,
                                                "title" to BsonString("Native Typed")
                                            )
                                        )
                                    )
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val collection = client.database("native_library").typedCollection<TestBook>("native_books")
                val insert = collection.insertOne(TestBook("Native Typed"))
                val id = insert.insertedId as BsonObjectId
                val found = collection.findOne(BsonDocument("_id" to id))
                assertEquals(TestBook("Native Typed"), found)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun runsDatabaseCommandsAgainstFakeOpMsgServer() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val create = receive()
                assertEquals(BsonString("native_commands"), create.body["create"])
                assertEquals(BsonString("native_library"), create.body["\$db"])
                reply(create, BsonDocument("ok" to BsonDouble(1.0)))

                val listCollections = receive()
                assertEquals(BsonInt32(1), listCollections.body["listCollections"])
                assertEquals(BsonBoolean(true), listCollections.body["nameOnly"])
                assertEquals(BsonString("native_library"), listCollections.body["\$db"])
                reply(
                    listCollections,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("native_library.\$cmd.listCollections"),
                                "firstBatch" to
                                    BsonArray(
                                        listOf(BsonDocument("name" to BsonString("native_commands")))
                                    )
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val drop = receive()
                assertEquals(BsonString("native_commands"), drop.body["drop"])
                assertEquals(BsonString("native_library"), drop.body["\$db"])
                reply(drop, BsonDocument("ok" to BsonDouble(1.0)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val database = client.database("native_library")
                assertEquals(1.0, database.createCollection("native_commands").ok)
                assertEquals(listOf("native_commands"), database.listCollectionNames())
                assertEquals(1.0, database.collection("native_commands").drop().ok)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun runsSessionTransactionAgainstFakeOpMsgServer() = runTest {
        val lsid = nativeSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectNativeStartSession(lsid)

                val insert = receive()
                assertEquals(BsonString("native_books"), insert.body["insert"])
                assertEquals(BsonString("native_library"), insert.body["\$db"])
                assertEquals(lsid, insert.body["lsid"])
                assertEquals(BsonInt64(1), insert.body["txnNumber"])
                assertEquals(BsonBoolean(true), insert.body["startTransaction"])
                assertEquals(BsonBoolean(false), insert.body["autocommit"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val commit = receive()
                assertEquals(BsonInt32(1), commit.body["commitTransaction"])
                assertEquals(BsonString("admin"), commit.body["\$db"])
                assertEquals(lsid, commit.body["lsid"])
                assertEquals(BsonInt64(1), commit.body["txnNumber"])
                assertEquals(BsonBoolean(false), commit.body["autocommit"])
                reply(commit, BsonDocument("ok" to BsonDouble(1.0)))

                expectNativeEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                client.withTransaction {
                    database("native_library")
                        .collection("native_books")
                        .insertOne(BsonDocument("name" to BsonString("Ada")))
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun runsIndexCommandsAgainstFakeOpMsgServer() = runTest {
        val keys = BsonDocument("title" to BsonInt32(1))

        withFakeMongoServer(
            handler = {
                expectHello()
                val createIndexes = receive()
                assertEquals(BsonString("native_books"), createIndexes.body["createIndexes"])
                assertEquals(BsonString("native_library"), createIndexes.body["\$db"])
                val index =
                    (createIndexes.body["indexes"] as BsonArray)
                        .values
                        .single() as BsonDocument
                assertEquals(keys, index["key"])
                assertEquals(BsonString("title_1"), index["name"])
                assertNull(index["unique"])
                reply(createIndexes, BsonDocument("ok" to BsonDouble(1.0)))

                val listIndexes = receive()
                assertEquals(BsonString("native_books"), listIndexes.body["listIndexes"])
                assertEquals(BsonString("native_library"), listIndexes.body["\$db"])
                reply(
                    listIndexes,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("native_library.native_books"),
                                "firstBatch" to
                                    BsonArray(
                                        listOf(
                                            BsonDocument("name" to BsonString("_id_")),
                                            BsonDocument("name" to BsonString("title_1"))
                                        )
                                    )
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val dropIndexes = receive()
                assertEquals(BsonString("native_books"), dropIndexes.body["dropIndexes"])
                assertEquals(BsonString("title_1"), dropIndexes.body["index"])
                assertEquals(BsonString("native_library"), dropIndexes.body["\$db"])
                reply(dropIndexes, BsonDocument("ok" to BsonDouble(1.0)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val collection = client.database("native_library").collection("native_books")
                assertEquals("title_1", collection.createIndex(keys))
                assertEquals(listOf("_id_", "title_1"), collection.listIndexNames())
                assertEquals(1.0, collection.dropIndex("title_1").ok)
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
    fun typedUpdatesAgainstFakeOpMsgServerUseSerializedFieldNames() = runTest {
        val id = BsonObjectId.fromHex("000000000000000000000002")

        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                assertEquals(BsonString("native_books"), updateCommand.body["update"])
                assertEquals(BsonString("native_library"), updateCommand.body["\$db"])
                val updates = updateCommand.body["updates"] as BsonArray
                val statement = updates.values.single() as BsonDocument
                assertEquals(BsonDocument("_id" to id), statement["q"])
                assertEquals(
                    BsonDocument("\$set" to BsonDocument("book_title" to BsonString("Dune"))),
                    statement["u"]
                )
                assertEquals(BsonBoolean(false), statement["multi"])
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
                        .typedCollection<NativeSerialNamedBook>("native_books")
                        .updateOne(
                            filter = { NativeSerialNamedBook::id eq id },
                            update = { set(NativeSerialNamedBook::title, "Dune") }
                        )
                assertEquals(1L, result.modifiedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun typedReplaceOneAgainstFakeOpMsgServerUsesSerializedFieldNames() = runTest {
        val id = BsonObjectId.fromHex("0000000000000000000000ab")

        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                assertEquals(BsonString("native_books"), updateCommand.body["update"])
                assertEquals(BsonString("native_library"), updateCommand.body["\$db"])
                val updates = updateCommand.body["updates"] as BsonArray
                val statement = updates.values.single() as BsonDocument
                assertEquals(BsonDocument("_id" to id), statement["q"])
                assertEquals(
                    BsonDocument(
                        "_id" to id,
                        "book_title" to BsonString("Dune Messiah"),
                        "published_year" to BsonInt32(1969)
                    ),
                    statement["u"]
                )
                assertEquals(BsonBoolean(false), statement["multi"])
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
                        .typedCollection<NativeSerialNamedBook>("native_books")
                        .replaceOne(
                            filter = { NativeSerialNamedBook::id eq id },
                            replacement = NativeSerialNamedBook(id = id, title = "Dune Messiah", year = 1969)
                        )
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
    fun createsListsAndDropsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val collectionName = "database_commands_${Random.nextInt(0, Int.MAX_VALUE)}"
        val client = MongoClient.connect(uri)
        try {
            val database = client.database("mongongo_native_smoke")
            assertEquals(1.0, database.createCollection(collectionName).ok)
            assertTrue(collectionName in database.listCollectionNames())
            assertEquals(1.0, database.collection(collectionName).drop().ok)
            assertTrue(collectionName !in database.listCollectionNames())
        } finally {
            client.close()
        }
    }

    @Test
    fun createsListsAndDropsIndexConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val collectionName = "index_commands_${Random.nextInt(0, Int.MAX_VALUE)}"
        val indexName = "name_1"
        val client = MongoClient.connect(uri)
        try {
            val collection = client.database("mongongo_native_smoke").collection(collectionName)
            assertEquals(indexName, collection.createIndex(BsonDocument("name" to BsonInt32(1))))
            assertTrue(indexName in collection.listIndexNames())
            assertEquals(1.0, collection.dropIndex(indexName).ok)
            assertTrue(indexName !in collection.listIndexNames())
        } finally {
            client.close()
        }
    }

    @Test
    fun commitsAndAbortsTransactionConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            try {
                val committedCollection =
                    client
                        .database("mongongo_native_smoke")
                        .collection("transaction_commit_${Random.nextInt(0, Int.MAX_VALUE)}")
                val abortedCollection =
                    client
                        .database("mongongo_native_smoke")
                        .collection("transaction_abort_${Random.nextInt(0, Int.MAX_VALUE)}")

                val committedId =
                    client.withTransaction {
                        val insertResult =
                            database("mongongo_native_smoke")
                                .collection(committedCollection.name)
                                .insertOne(BsonDocument("name" to BsonString("committed")))
                        assertIs<BsonObjectId>(insertResult.insertedId)
                    }
                assertEquals(BsonString("committed"), committedCollection.findOne(BsonDocument("_id" to committedId))?.get("name"))

                var abortedId: BsonObjectId? = null
                val failure =
                    assertFailsWith<IllegalStateException> {
                        client.withTransaction {
                            val insertResult =
                                database("mongongo_native_smoke")
                                    .collection(abortedCollection.name)
                                    .insertOne(BsonDocument("name" to BsonString("aborted")))
                            abortedId = assertIs<BsonObjectId>(insertResult.insertedId)
                            error("rollback")
                        }
                    }
                assertEquals("rollback", failure.message)
                assertNull(abortedCollection.findOne(BsonDocument("_id" to abortedId!!)))
            } catch (exception: MongoCommandException) {
                if (exception.isTransactionSupportFailure()) {
                    return@runTest
                }
                throw exception
            }
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
    fun typedCollectionInsertsAndFindsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            val collection =
                client
                    .database("mongongo_native_smoke")
                    .typedCollection<TestBook>("typed_${Random.nextInt(0, Int.MAX_VALUE)}")
            val insertResult = collection.insertOne(TestBook("native-typed"))
            val insertedId = assertIs<BsonObjectId>(insertResult.insertedId)
            val found = collection.findOne(BsonDocument("_id" to insertedId))
            assertEquals(TestBook("native-typed"), found)
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

private suspend fun FakeMongoConnection.expectNativeStartSession(lsid: BsonDocument) {
    val startSession = receive()
    assertEquals(BsonInt32(1), startSession.body["startSession"])
    assertEquals(BsonString("admin"), startSession.body["\$db"])
    reply(
        startSession,
        BsonDocument(
            "id" to lsid,
            "timeoutMinutes" to BsonInt32(30),
            "ok" to BsonDouble(1.0)
        )
    )
}

private suspend fun FakeMongoConnection.expectNativeEndSessions(lsid: BsonDocument) {
    val endSessions = receive()
    assertEquals(BsonArray(listOf(lsid)), endSessions.body["endSessions"])
    assertEquals(BsonString("admin"), endSessions.body["\$db"])
    reply(endSessions, BsonDocument("ok" to BsonDouble(1.0)))
}

private fun nativeSessionId(): BsonDocument =
    BsonDocument(
        "id" to
            BsonBinary(
                subtype = 4,
                bytes = (16 until 32).map { it.toByte() }
            )
    )

private fun MongoCommandException.isTransactionSupportFailure(): Boolean {
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
