package mongongo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MongoCollectionTest {
    @Test
    fun findOneSendsFindCommandAndReturnsFirstBatchDocument() = runTest {
        val filter = BsonDocument("name" to BsonString("Ada"))
        val found =
            BsonDocument(
                "_id" to BsonString("known-id"),
                "name" to BsonString("Ada")
            )

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(listOf("find", "filter", "limit", "singleBatch", "\$db"), find.body.values.keys.toList())
                assertEquals(BsonString("books"), find.body["find"])
                assertEquals(filter, find.body["filter"])
                assertEquals(BsonInt32(1), find.body["limit"])
                assertEquals(BsonBoolean(true), find.body["singleBatch"])
                assertEquals(BsonString("library"), find.body["\$db"])

                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(listOf(found))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").findOne(filter)
                assertEquals(found, result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findOneReturnsNullForEmptyFirstBatch() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(emptyList())
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertNull(client.database("library").collection("books").findOne())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findOneFailsOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                reply(
                    find,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(13),
                        "errmsg" to BsonString("not authorized")
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoCommandException> {
                    client.database("library").collection("books").findOne()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findOneFailsWhenCursorRemainsOpen() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(42),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(emptyList())
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<IllegalStateException> {
                    client.database("library").collection("books").findOne()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertOneGeneratesObjectIdAndSendsInsertCommand() = runTest {
        var sentDocument: BsonDocument? = null

        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                assertEquals(listOf("insert", "documents", "ordered", "\$db"), insert.body.values.keys.toList())
                assertEquals(BsonString("books"), insert.body["insert"])
                assertEquals(BsonBoolean(true), insert.body["ordered"])
                assertEquals(BsonString("library"), insert.body["\$db"])

                val documents = insert.body["documents"].asBson<BsonArray>()
                assertEquals(1, documents.values.size)
                val document = documents.values.single().asBson<BsonDocument>()
                val generatedId = document["_id"].asBson<BsonObjectId>()
                assertEquals(12, generatedId.bytes.size)
                assertEquals(BsonString("Ada"), document["name"])
                sentDocument = document

                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val original = BsonDocument("name" to BsonString("Ada"))
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").insertOne(original)
                val generatedId = sentDocument?.get("_id").asBson<BsonObjectId>()
                assertTrue(result.acknowledged)
                assertEquals(generatedId, result.insertedId)
                assertNull(original["_id"])
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertOneKeepsExistingId() = runTest {
        val id = BsonString("known-id")
        val original = BsonDocument("_id" to id, "name" to BsonString("Ada"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                val document = insert.body["documents"].asBson<BsonArray>().values.single().asBson<BsonDocument>()
                assertEquals(original, document)
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").insertOne(original)
                assertTrue(result.acknowledged)
                assertEquals(id, result.insertedId)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertOneFailsOnWriteErrors() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                reply(
                    insert,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(0),
                        "writeErrors" to
                            BsonArray(
                                listOf(
                                    BsonDocument(
                                        "index" to BsonInt32(0),
                                        "code" to BsonInt32(11_000),
                                        "errmsg" to BsonString("duplicate key")
                                    )
                                )
                            )
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoWriteException> {
                    client.database("library").collection("books").insertOne(BsonDocument("name" to BsonString("Ada")))
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertOneFailsOnWriteConcernError() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                reply(
                    insert,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(1),
                        "writeConcernError" to
                            BsonDocument(
                                "code" to BsonInt32(64),
                                "errmsg" to BsonString("write concern failed")
                            )
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoWriteException> {
                    client.database("library").collection("books").insertOne(BsonDocument("name" to BsonString("Ada")))
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteOneSendsDeleteCommandAndReturnsDeletedCount() = runTest {
        val filter = BsonDocument("name" to BsonString("Ada"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                assertEquals(listOf("delete", "deletes", "ordered", "\$db"), delete.body.values.keys.toList())
                assertEquals(BsonString("books"), delete.body["delete"])
                assertEquals(BsonBoolean(true), delete.body["ordered"])
                assertEquals(BsonString("library"), delete.body["\$db"])

                val deletes = delete.body["deletes"].asBson<BsonArray>()
                assertEquals(1, deletes.values.size)
                val statement = deletes.values.single().asBson<BsonDocument>()
                assertEquals(listOf("q", "limit"), statement.values.keys.toList())
                assertEquals(filter, statement["q"])
                assertEquals(BsonInt32(1), statement["limit"])

                reply(delete, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").deleteOne(filter)
                assertTrue(result.acknowledged)
                assertEquals(1L, result.deletedCount)
                assertEquals(BsonInt32(1), result.raw["n"])
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteOneReturnsZeroDeletedCountAndAllowsEmptyFilter() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                val statement =
                    delete
                        .body["deletes"]
                        .asBson<BsonArray>()
                        .values
                        .single()
                        .asBson<BsonDocument>()
                assertEquals(BsonDocument(), statement["q"])
                assertEquals(BsonInt32(1), statement["limit"])
                reply(delete, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(0)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").deleteOne(BsonDocument())
                assertEquals(0L, result.deletedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteOneFailsOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                reply(
                    delete,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(13),
                        "errmsg" to BsonString("not authorized")
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoCommandException> {
                    client.database("library").collection("books").deleteOne(BsonDocument())
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteOneFailsOnWriteErrors() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                reply(
                    delete,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(0),
                        "writeErrors" to
                            BsonArray(
                                listOf(
                                    BsonDocument(
                                        "index" to BsonInt32(0),
                                        "code" to BsonInt32(2),
                                        "errmsg" to BsonString("bad query")
                                    )
                                )
                            )
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoWriteException> {
                    client.database("library").collection("books").deleteOne(BsonDocument())
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteOneFailsOnWriteConcernError() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                reply(
                    delete,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(1),
                        "writeConcernError" to
                            BsonDocument(
                                "code" to BsonInt32(64),
                                "errmsg" to BsonString("write concern failed")
                            )
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoWriteException> {
                    client.database("library").collection("books").deleteOne(BsonDocument())
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneSendsUpdateCommandAndReturnsMatchedAndModifiedCounts() = runTest {
        val filter = BsonDocument("name" to BsonString("Ada"))
        val update = BsonDocument("\$set" to BsonDocument("role" to BsonString("writer")))

        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                assertEquals(listOf("update", "updates", "ordered", "\$db"), updateCommand.body.values.keys.toList())
                assertEquals(BsonString("books"), updateCommand.body["update"])
                assertEquals(BsonBoolean(true), updateCommand.body["ordered"])
                assertEquals(BsonString("library"), updateCommand.body["\$db"])

                val updates = updateCommand.body["updates"].asBson<BsonArray>()
                assertEquals(1, updates.values.size)
                val statement = updates.values.single().asBson<BsonDocument>()
                assertEquals(listOf("q", "u", "multi", "upsert"), statement.values.keys.toList())
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
                val result = client.database("library").collection("books").updateOne(filter, update)
                assertTrue(result.acknowledged)
                assertEquals(1L, result.matchedCount)
                assertEquals(1L, result.modifiedCount)
                assertNull(result.upsertedId)
                assertEquals(BsonInt32(1), result.raw["nModified"])
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneReturnsZeroMatchedAndModifiedCounts() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                val statement =
                    updateCommand
                        .body["updates"]
                        .asBson<BsonArray>()
                        .values
                        .single()
                        .asBson<BsonDocument>()
                assertEquals(BsonDocument(), statement["q"])
                assertEquals(BsonBoolean(false), statement["multi"])
                reply(
                    updateCommand,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(0),
                        "nModified" to BsonInt32(0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("library")
                        .collection("books")
                        .updateOne(
                            filter = BsonDocument(),
                            update = BsonDocument("\$set" to BsonDocument("role" to BsonString("writer")))
                        )
                assertEquals(0L, result.matchedCount)
                assertEquals(0L, result.modifiedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneReturnsUpsertedId() = runTest {
        val upsertedId = BsonObjectId.fromHex("00112233445566778899aabb")

        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                val statement =
                    updateCommand
                        .body["updates"]
                        .asBson<BsonArray>()
                        .values
                        .single()
                        .asBson<BsonDocument>()
                assertEquals(BsonBoolean(true), statement["upsert"])
                reply(
                    updateCommand,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(1),
                        "nModified" to BsonInt32(0),
                        "upserted" to
                            BsonArray(
                                listOf(
                                    BsonDocument(
                                        "index" to BsonInt32(0),
                                        "_id" to upsertedId
                                    )
                                )
                            )
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("library")
                        .collection("books")
                        .updateOne(
                            filter = BsonDocument("name" to BsonString("Ada")),
                            update = BsonDocument("\$set" to BsonDocument("role" to BsonString("writer"))),
                            upsert = true
                        )
                assertEquals(1L, result.matchedCount)
                assertEquals(0L, result.modifiedCount)
                assertEquals(upsertedId, result.upsertedId)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneRejectsReplacementDocument() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<IllegalArgumentException> {
                    client
                        .database("library")
                        .collection("books")
                        .updateOne(
                            filter = BsonDocument("name" to BsonString("Ada")),
                            update = BsonDocument("name" to BsonString("Grace"))
                        )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneFailsOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                reply(
                    updateCommand,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(13),
                        "errmsg" to BsonString("not authorized")
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoCommandException> {
                    client
                        .database("library")
                        .collection("books")
                        .updateOne(
                            filter = BsonDocument(),
                            update = BsonDocument("\$set" to BsonDocument("name" to BsonString("Ada")))
                        )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneFailsOnWriteErrors() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                reply(
                    updateCommand,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(0),
                        "nModified" to BsonInt32(0),
                        "writeErrors" to
                            BsonArray(
                                listOf(
                                    BsonDocument(
                                        "index" to BsonInt32(0),
                                        "code" to BsonInt32(2),
                                        "errmsg" to BsonString("bad update")
                                    )
                                )
                            )
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoWriteException> {
                    client
                        .database("library")
                        .collection("books")
                        .updateOne(
                            filter = BsonDocument(),
                            update = BsonDocument("\$set" to BsonDocument("name" to BsonString("Ada")))
                        )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneFailsOnWriteConcernError() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                reply(
                    updateCommand,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(1),
                        "nModified" to BsonInt32(1),
                        "writeConcernError" to
                            BsonDocument(
                                "code" to BsonInt32(64),
                                "errmsg" to BsonString("write concern failed")
                            )
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoWriteException> {
                    client
                        .database("library")
                        .collection("books")
                        .updateOne(
                            filter = BsonDocument(),
                            update = BsonDocument("\$set" to BsonDocument("name" to BsonString("Ada")))
                        )
                }
            } finally {
                client.close()
            }
        }
    }
}

private inline fun <reified T : BsonValue> BsonValue?.asBson(): T =
    this as? T ?: error("Unexpected BSON value $this")
