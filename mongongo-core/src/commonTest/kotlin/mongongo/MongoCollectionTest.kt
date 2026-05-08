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
}

private inline fun <reified T : BsonValue> BsonValue?.asBson(): T =
    this as? T ?: error("Unexpected BSON value $this")
