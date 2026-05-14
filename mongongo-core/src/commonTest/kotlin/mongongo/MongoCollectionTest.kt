package mongongo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MongoCollectionTest {
    @Test
    fun bsonDocumentCodecEncodesAndDecodesIdentity() {
        val document = BsonDocument("name" to BsonString("Ada"))

        assertTrue(BsonDocumentCodec.encode(document) === document)
        assertTrue(BsonDocumentCodec.decode(document) === document)
    }

    @Test
    fun defaultCollectionReturnsBsonDocumentCollection() = runTest {
        val found = BsonDocument("_id" to BsonString("known-id"), "name" to BsonString("Ada"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(BsonString("books"), find.body["find"])
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
                val collection: BsonCollection = client.database("library").collection("books")
                assertEquals(found, collection.findOne())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun typedInsertOneEncodesValueIntoInsertCommand() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                assertEquals(listOf("insert", "documents", "ordered", "\$db"), insert.body.values.keys.toList())
                assertEquals(BsonString("books"), insert.body["insert"])
                assertEquals(BsonString("library"), insert.body["\$db"])

                val document =
                    insert
                        .body["documents"]
                        .asBson<BsonArray>()
                        .values
                        .single()
                        .asBson<BsonDocument>()
                assertTrue(document["_id"] is BsonObjectId)
                assertEquals(BsonString("Parable of the Sower"), document["title"])

                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("library")
                        .typedCollection<TestBook>("books")
                        .insertOne(TestBook("Parable of the Sower"))
                assertTrue(result.insertedId is BsonObjectId)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun typedFindOneDecodesFirstBatchDocument() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(BsonString("books"), find.body["find"])
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(listOf(BsonDocument("title" to BsonString("Kindred"))))
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
                        .database("library")
                        .collection("books", TestBook.serializer())
                        .findOne()
                assertEquals(TestBook("Kindred"), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun typedFindDecodesFirstAndNextBatchDocuments() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(BsonString("books"), find.body["find"])
                assertEquals(BsonInt32(1), find.body["batchSize"])
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(123),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(listOf(BsonDocument("title" to BsonString("Dawn"))))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val getMore = receive()
                assertEquals(BsonInt64(123), getMore.body["getMore"])
                assertEquals(BsonString("books"), getMore.body["collection"])
                assertEquals(BsonString("library"), getMore.body["\$db"])
                reply(
                    getMore,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "nextBatch" to BsonArray(listOf(BsonDocument("title" to BsonString("Adulthood Rites"))))
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
                        .database("library")
                        .collection("books", TestBookCodec)
                        .find(batchSize = 1)
                        .toList()
                assertEquals(listOf(TestBook("Dawn"), TestBook("Adulthood Rites")), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun typedFindPropagatesCodecDecodeFailure() = runTest {
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
                                "firstBatch" to BsonArray(listOf(BsonDocument("title" to BsonString("Broken"))))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val cursor =
                    client
                        .database("library")
                        .collection("books", FailingTestBookCodec)
                        .find()
                val failure = assertFailsWith<IllegalStateException> { cursor.toList() }
                assertEquals("decode failed", failure.message)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun listCollectionNamesSendsListCollectionsCommandAndReturnsFirstBatchNames() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val listCollections = receive()
                assertEquals(listOf("listCollections", "nameOnly", "\$db"), listCollections.body.values.keys.toList())
                assertEquals(BsonInt32(1), listCollections.body["listCollections"])
                assertEquals(BsonBoolean(true), listCollections.body["nameOnly"])
                assertEquals(BsonString("library"), listCollections.body["\$db"])

                reply(
                    listCollections,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.\$cmd.listCollections"),
                                "firstBatch" to
                                    BsonArray(
                                        listOf(
                                            BsonDocument("name" to BsonString("books")),
                                            BsonDocument("name" to BsonString("authors"))
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
                val result = client.database("library").listCollectionNames()
                assertEquals(listOf("books", "authors"), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun listCollectionNamesUsesGetMoreWhenCursorRemainsOpen() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val listCollections = receive()
                reply(
                    listCollections,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(123),
                                "ns" to BsonString("library.\$cmd.listCollections"),
                                "firstBatch" to BsonArray(listOf(BsonDocument("name" to BsonString("books"))))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val getMore = receive()
                assertEquals(listOf("getMore", "collection", "\$db"), getMore.body.values.keys.toList())
                assertEquals(BsonInt64(123), getMore.body["getMore"])
                assertEquals(BsonString("\$cmd.listCollections"), getMore.body["collection"])
                assertEquals(BsonString("library"), getMore.body["\$db"])
                reply(
                    getMore,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.\$cmd.listCollections"),
                                "nextBatch" to BsonArray(listOf(BsonDocument("name" to BsonString("authors"))))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").listCollectionNames()
                assertEquals(listOf("books", "authors"), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun listCollectionNamesFailsOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val listCollections = receive()
                reply(
                    listCollections,
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
                    client.database("library").listCollectionNames()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun createCollectionSendsCreateCommand() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val create = receive()
                assertEquals(listOf("create", "\$db"), create.body.values.keys.toList())
                assertEquals(BsonString("books"), create.body["create"])
                assertEquals(BsonString("library"), create.body["\$db"])
                reply(create, BsonDocument("ok" to BsonDouble(1.0)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").createCollection("books")
                assertEquals(1.0, result.ok)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun createCollectionRejectsBlankName() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<IllegalArgumentException> {
                    client.database("library").createCollection(" ")
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun dropSendsDropCommand() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val drop = receive()
                assertEquals(listOf("drop", "\$db"), drop.body.values.keys.toList())
                assertEquals(BsonString("books"), drop.body["drop"])
                assertEquals(BsonString("library"), drop.body["\$db"])
                reply(drop, BsonDocument("ok" to BsonDouble(1.0), "ns" to BsonString("library.books")))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").drop()
                assertEquals(1.0, result.ok)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun dropFailsOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val drop = receive()
                reply(
                    drop,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(26),
                        "errmsg" to BsonString("ns not found")
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoCommandException> {
                    client.database("library").collection("books").drop()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun createIndexSendsCreateIndexesCommandAndReturnsGeneratedName() = runTest {
        val keys = BsonDocument("name" to BsonInt32(1), "age" to BsonInt32(-1))

        withFakeMongoServer(
            handler = {
                expectHello()
                val createIndexes = receive()
                assertEquals(listOf("createIndexes", "indexes", "\$db"), createIndexes.body.values.keys.toList())
                assertEquals(BsonString("books"), createIndexes.body["createIndexes"])
                assertEquals(BsonString("library"), createIndexes.body["\$db"])

                val indexes = createIndexes.body["indexes"].asBson<BsonArray>()
                val index = indexes.values.single().asBson<BsonDocument>()
                assertEquals(listOf("key", "name"), index.values.keys.toList())
                assertEquals(keys, index["key"])
                assertEquals(BsonString("name_1_age_-1"), index["name"])
                assertNull(index["unique"])

                reply(createIndexes, BsonDocument("ok" to BsonDouble(1.0)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").createIndex(keys)
                assertEquals("name_1_age_-1", result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun createIndexSendsExplicitNameAndUniqueTrue() = runTest {
        val keys = BsonDocument("email" to BsonInt32(1))

        withFakeMongoServer(
            handler = {
                expectHello()
                val createIndexes = receive()
                val index =
                    createIndexes
                        .body["indexes"]
                        .asBson<BsonArray>()
                        .values
                        .single()
                        .asBson<BsonDocument>()
                assertEquals(listOf("key", "name", "unique"), index.values.keys.toList())
                assertEquals(keys, index["key"])
                assertEquals(BsonString("email_unique"), index["name"])
                assertEquals(BsonBoolean(true), index["unique"])
                reply(createIndexes, BsonDocument("ok" to BsonDouble(1.0)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("library")
                        .collection("books")
                        .createIndex(keys = keys, name = "email_unique", unique = true)
                assertEquals("email_unique", result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun createIndexRejectsEmptyKeys() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        client.database("library").collection("books").createIndex(BsonDocument())
                    }
                assertEquals("createIndex requires at least one key", failure.message)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun createIndexFailsOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val createIndexes = receive()
                reply(
                    createIndexes,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(85),
                        "errmsg" to BsonString("index options conflict")
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoCommandException> {
                    client.database("library").collection("books").createIndex(BsonDocument("name" to BsonInt32(1)))
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun listIndexNamesUsesGetMoreWhenCursorRemainsOpen() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val listIndexes = receive()
                assertEquals(listOf("listIndexes", "\$db"), listIndexes.body.values.keys.toList())
                assertEquals(BsonString("books"), listIndexes.body["listIndexes"])
                assertEquals(BsonString("library"), listIndexes.body["\$db"])
                reply(
                    listIndexes,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(987),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to
                                    BsonArray(
                                        listOf(
                                            BsonDocument("name" to BsonString("_id_")),
                                            BsonDocument("name" to BsonString("name_1"))
                                        )
                                    )
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val getMore = receive()
                assertEquals(listOf("getMore", "collection", "\$db"), getMore.body.values.keys.toList())
                assertEquals(BsonInt64(987), getMore.body["getMore"])
                assertEquals(BsonString("books"), getMore.body["collection"])
                assertEquals(BsonString("library"), getMore.body["\$db"])
                reply(
                    getMore,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "nextBatch" to BsonArray(listOf(BsonDocument("name" to BsonString("age_-1"))))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").listIndexNames()
                assertEquals(listOf("_id_", "name_1", "age_-1"), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun listIndexNamesFailsOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val listIndexes = receive()
                reply(
                    listIndexes,
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
                    client.database("library").collection("books").listIndexNames()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun dropIndexSendsDropIndexesCommand() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val dropIndexes = receive()
                assertEquals(listOf("dropIndexes", "index", "\$db"), dropIndexes.body.values.keys.toList())
                assertEquals(BsonString("books"), dropIndexes.body["dropIndexes"])
                assertEquals(BsonString("name_1"), dropIndexes.body["index"])
                assertEquals(BsonString("library"), dropIndexes.body["\$db"])
                reply(
                    dropIndexes,
                    BsonDocument(
                        "nIndexesWas" to BsonInt32(2),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").dropIndex("name_1")
                assertEquals(1.0, result.ok)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun dropIndexRejectsBlankName() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<IllegalArgumentException> {
                    client.database("library").collection("books").dropIndex(" ")
                }
            } finally {
                client.close()
            }
        }
    }

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
    fun findSendsFindCommandWithOptions() = runTest {
        val filter = BsonDocument("role" to BsonString("writer"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(listOf("find", "filter", "limit", "batchSize", "\$db"), find.body.values.keys.toList())
                assertEquals(BsonString("books"), find.body["find"])
                assertEquals(filter, find.body["filter"])
                assertEquals(BsonInt32(7), find.body["limit"])
                assertEquals(BsonInt32(2), find.body["batchSize"])
                assertEquals(BsonString("library"), find.body["\$db"])
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
                val result =
                    client
                        .database("library")
                        .collection("books")
                        .find(filter = filter, limit = 7, batchSize = 2)
                        .toList()
                assertEquals(emptyList(), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findReturnsAllFirstBatchDocumentsWhenCursorIsClosed() = runTest {
        val first = BsonDocument("_id" to BsonString("first"), "name" to BsonString("Ada"))
        val second = BsonDocument("_id" to BsonString("second"), "name" to BsonString("Grace"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(listOf("find", "filter", "\$db"), find.body.values.keys.toList())
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(listOf(first, second))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").find().toList()
                assertEquals(listOf(first, second), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findUsesGetMoreWhenCursorRemainsOpen() = runTest {
        val first = BsonDocument("_id" to BsonString("first"), "name" to BsonString("Ada"))
        val second = BsonDocument("_id" to BsonString("second"), "name" to BsonString("Grace"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(123),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(listOf(first))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val getMore = receive()
                assertEquals(listOf("getMore", "collection", "\$db"), getMore.body.values.keys.toList())
                assertEquals(BsonInt64(123), getMore.body["getMore"])
                assertEquals(BsonString("books"), getMore.body["collection"])
                assertEquals(BsonString("library"), getMore.body["\$db"])
                reply(
                    getMore,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "nextBatch" to BsonArray(listOf(second))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").find().toList()
                assertEquals(listOf(first, second), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findCloseKillsOpenCursor() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(456),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(emptyList())
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val killCursors = receive()
                assertEquals(listOf("killCursors", "cursors", "\$db"), killCursors.body.values.keys.toList())
                assertEquals(BsonString("books"), killCursors.body["killCursors"])
                assertEquals(BsonArray(listOf(BsonInt64(456))), killCursors.body["cursors"])
                assertEquals(BsonString("library"), killCursors.body["\$db"])
                reply(
                    killCursors,
                    BsonDocument(
                        "cursorsKilled" to BsonArray(listOf(BsonInt64(456))),
                        "cursorsNotFound" to BsonArray(emptyList()),
                        "cursorsAlive" to BsonArray(emptyList()),
                        "cursorsUnknown" to BsonArray(emptyList()),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val cursor = client.database("library").collection("books").find()
                cursor.close()
                cursor.close()
                assertNull(cursor.next())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findToListKillsOpenCursorWhenDecodeFails() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(789),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(listOf(BsonDocument("name" to BsonString("Ada"))))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val killCursors = receive()
                assertEquals(BsonString("books"), killCursors.body["killCursors"])
                assertEquals(BsonArray(listOf(BsonInt64(789))), killCursors.body["cursors"])
                reply(
                    killCursors,
                    BsonDocument(
                        "cursorsKilled" to BsonArray(listOf(BsonInt64(789))),
                        "cursorsNotFound" to BsonArray(emptyList()),
                        "cursorsAlive" to BsonArray(emptyList()),
                        "cursorsUnknown" to BsonArray(emptyList()),
                        "ok" to BsonDouble(1.0)
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val cursor = client.database("library").collection("books", FailingDecodeCodec).find()
                val failure =
                    assertFailsWith<IllegalStateException> {
                        cursor.toList()
                    }
                assertEquals("decode failed", failure.message)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findFailsOnCommandFailure() = runTest {
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
                    client.database("library").collection("books").find()
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
    fun insertManyGeneratesObjectIdsAndSendsInsertCommand() = runTest {
        var sentDocuments = emptyList<BsonDocument>()
        val first = BsonDocument("name" to BsonString("Ada"))
        val second = BsonDocument("name" to BsonString("Grace"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                assertEquals(listOf("insert", "documents", "ordered", "\$db"), insert.body.values.keys.toList())
                assertEquals(BsonString("books"), insert.body["insert"])
                assertEquals(BsonBoolean(true), insert.body["ordered"])
                assertEquals(BsonString("library"), insert.body["\$db"])

                val documents = insert.body["documents"].asBson<BsonArray>()
                assertEquals(2, documents.values.size)
                val sentFirst = documents.values[0].asBson<BsonDocument>()
                val sentSecond = documents.values[1].asBson<BsonDocument>()
                val firstId = sentFirst["_id"].asBson<BsonObjectId>()
                val secondId = sentSecond["_id"].asBson<BsonObjectId>()
                assertEquals(12, firstId.bytes.size)
                assertEquals(12, secondId.bytes.size)
                assertEquals(BsonString("Ada"), sentFirst["name"])
                assertEquals(BsonString("Grace"), sentSecond["name"])
                sentDocuments = listOf(sentFirst, sentSecond)

                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(2)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").insertMany(listOf(first, second))
                assertTrue(result.acknowledged)
                assertEquals(sentDocuments[0]["_id"], result.insertedIds[0])
                assertEquals(sentDocuments[1]["_id"], result.insertedIds[1])
                assertNull(first["_id"])
                assertNull(second["_id"])
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertManyKeepsExistingIds() = runTest {
        val firstId = BsonString("first-id")
        val secondId = BsonObjectId.fromHex("00112233445566778899aabb")
        val first = BsonDocument("_id" to firstId, "name" to BsonString("Ada"))
        val second = BsonDocument("_id" to secondId, "name" to BsonString("Grace"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                val documents = insert.body["documents"].asBson<BsonArray>()
                assertEquals(listOf(first, second), documents.values)
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(2)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").insertMany(listOf(first, second))
                assertTrue(result.acknowledged)
                assertEquals(linkedMapOf(0 to firstId, 1 to secondId), result.insertedIds)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertManyRejectsEmptyDocumentList() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        client.database("library").collection("books").insertMany(emptyList())
                    }
                assertEquals("insertMany requires at least one document", failure.message)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertManySendsOrderedFalse() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                assertEquals(BsonBoolean(false), insert.body["ordered"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("library")
                        .collection("books")
                        .insertMany(listOf(BsonDocument("name" to BsonString("Ada"))), ordered = false)
                assertTrue(result.acknowledged)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertManyFailsOnCommandFailure() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                reply(
                    insert,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(13),
                        "errmsg" to BsonString("unauthorized")
                    )
                )
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoCommandException> {
                    client.database("library").collection("books").insertMany(listOf(BsonDocument()))
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertManyFailsOnWriteErrors() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                reply(
                    insert,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(1),
                        "writeErrors" to
                            BsonArray(
                                listOf(
                                    BsonDocument(
                                        "index" to BsonInt32(1),
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
                    client
                        .database("library")
                        .collection("books")
                        .insertMany(
                            listOf(
                                BsonDocument("name" to BsonString("Ada")),
                                BsonDocument("name" to BsonString("Ada"))
                            )
                        )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun insertManyFailsOnWriteConcernError() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                reply(
                    insert,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(2),
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
                        .insertMany(
                            listOf(
                                BsonDocument("name" to BsonString("Ada")),
                                BsonDocument("name" to BsonString("Grace"))
                            )
                        )
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
    fun deleteManySendsDeleteCommandAndReturnsDeletedCount() = runTest {
        val filter = BsonDocument("status" to BsonString("archived"))

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
                assertEquals(BsonInt32(0), statement["limit"])

                reply(delete, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(2)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").deleteMany(filter)
                assertTrue(result.acknowledged)
                assertEquals(2L, result.deletedCount)
                assertEquals(BsonInt32(2), result.raw["n"])
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteManyReturnsZeroDeletedCountAndAllowsEmptyFilter() = runTest {
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
                assertEquals(BsonInt32(0), statement["limit"])
                reply(delete, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(0)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result = client.database("library").collection("books").deleteMany(BsonDocument())
                assertEquals(0L, result.deletedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteManySendsOrderedFalse() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                assertEquals(BsonBoolean(false), delete.body["ordered"])
                val statement =
                    delete
                        .body["deletes"]
                        .asBson<BsonArray>()
                        .values
                        .single()
                        .asBson<BsonDocument>()
                assertEquals(BsonInt32(0), statement["limit"])
                reply(delete, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client
                        .database("library")
                        .collection("books")
                        .deleteMany(BsonDocument("status" to BsonString("archived")), ordered = false)
                assertEquals(1L, result.deletedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteManyFailsOnCommandFailure() = runTest {
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
                    client.database("library").collection("books").deleteMany(BsonDocument())
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteManyFailsOnWriteErrors() = runTest {
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
                    client.database("library").collection("books").deleteMany(BsonDocument())
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deleteManyFailsOnWriteConcernError() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val delete = receive()
                reply(
                    delete,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(2),
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
                    client.database("library").collection("books").deleteMany(BsonDocument())
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

    @Test
    fun updateManySendsUpdateCommandAndReturnsMatchedAndModifiedCounts() = runTest {
        val filter = BsonDocument("status" to BsonString("queued"))
        val update = BsonDocument("\$set" to BsonDocument("status" to BsonString("done")))

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
                assertEquals(BsonBoolean(true), statement["multi"])
                assertEquals(BsonBoolean(false), statement["upsert"])

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
                val result = client.database("library").collection("books").updateMany(filter, update)
                assertTrue(result.acknowledged)
                assertEquals(2L, result.matchedCount)
                assertEquals(2L, result.modifiedCount)
                assertNull(result.upsertedId)
                assertEquals(BsonInt32(2), result.raw["nModified"])
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateManyReturnsZeroMatchedAndModifiedCountsAndAllowsEmptyFilter() = runTest {
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
                assertEquals(BsonBoolean(true), statement["multi"])
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
                        .updateMany(
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
    fun updateManyReturnsUpsertedId() = runTest {
        val upsertedId = BsonObjectId.fromHex("2233445566778899aabbccdd")

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
                assertEquals(BsonBoolean(true), statement["multi"])
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
                        .updateMany(
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
    fun updateManyRejectsReplacementDocument() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        client
                            .database("library")
                            .collection("books")
                            .updateMany(
                                filter = BsonDocument("name" to BsonString("Ada")),
                                update = BsonDocument("name" to BsonString("Grace"))
                            )
                    }
                assertEquals("updateMany only supports update operator documents", failure.message)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateManyFailsOnCommandFailure() = runTest {
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
                        .updateMany(
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
    fun updateManyFailsOnWriteErrors() = runTest {
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
                        .updateMany(
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
    fun updateManyFailsOnWriteConcernError() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val updateCommand = receive()
                reply(
                    updateCommand,
                    BsonDocument(
                        "ok" to BsonDouble(1.0),
                        "n" to BsonInt32(2),
                        "nModified" to BsonInt32(2),
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
                        .updateMany(
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
    fun replaceOneSendsUpdateCommandAndReturnsMatchedAndModifiedCounts() = runTest {
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
                assertEquals(listOf("update", "updates", "ordered", "\$db"), updateCommand.body.values.keys.toList())
                assertEquals(BsonString("books"), updateCommand.body["update"])
                assertEquals(BsonBoolean(true), updateCommand.body["ordered"])
                assertEquals(BsonString("library"), updateCommand.body["\$db"])

                val updates = updateCommand.body["updates"].asBson<BsonArray>()
                assertEquals(1, updates.values.size)
                val statement = updates.values.single().asBson<BsonDocument>()
                assertEquals(listOf("q", "u", "multi", "upsert"), statement.values.keys.toList())
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
                val result = client.database("library").collection("books").replaceOne(filter, replacement)
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
    fun replaceOneReturnsUpsertedId() = runTest {
        val upsertedId = BsonObjectId.fromHex("112233445566778899aabbcc")
        val replacement = BsonDocument("name" to BsonString("Grace"))

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
                assertEquals(replacement, statement["u"])
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
                        .replaceOne(
                            filter = BsonDocument("name" to BsonString("Grace")),
                            replacement = replacement,
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
    fun replaceOneRejectsOperatorDocument() = runTest {
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
                        .replaceOne(
                            filter = BsonDocument("name" to BsonString("Ada")),
                            replacement = BsonDocument("\$set" to BsonDocument("name" to BsonString("Grace")))
                        )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun replaceOneFailsOnCommandFailure() = runTest {
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
                        .replaceOne(
                            filter = BsonDocument(),
                            replacement = BsonDocument("name" to BsonString("Ada"))
                        )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun replaceOneFailsOnWriteErrors() = runTest {
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
                                        "code" to BsonInt32(66),
                                        "errmsg" to BsonString("immutable field")
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
                        .replaceOne(
                            filter = BsonDocument(),
                            replacement = BsonDocument("name" to BsonString("Ada"))
                        )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun replaceOneFailsOnWriteConcernError() = runTest {
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
                        .replaceOne(
                            filter = BsonDocument(),
                            replacement = BsonDocument("name" to BsonString("Ada"))
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

private object FailingDecodeCodec : MongoCodec<BsonDocument> {
    override fun encode(value: BsonDocument): BsonDocument = value

    override fun decode(document: BsonDocument): BsonDocument =
        error("decode failed")
}
