package mongongo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class SerialNamedDslBook(
    @SerialName("book_title")
    val title: String,
    @SerialName("published_year")
    val year: Int = 0
)

@Serializable
private data class SerialNamedUpdateDslBook(
    @SerialName("_id")
    val id: BsonObjectId,
    @SerialName("book_title")
    val title: String,
    @SerialName("published_year")
    val year: Int = 0
)

class MongoCollectionDslTest {
    @Test
    fun insertOneDslBlockBuildsBsonDocument() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val insert = receive()
                val document =
                    insert
                        .body["documents"]
                        .asBson<BsonArray>()
                        .values
                        .single()
                        .asBson<BsonDocument>()

                assertTrue(document["_id"] is BsonObjectId)
                assertEquals(BsonString("Dune"), document["title"])
                assertEquals(BsonInt32(1965), document["year"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val result =
                    client.database("library").collection("books").insertOne {
                        value("title", "Dune")
                        value("year", 1965)
                    }
                assertTrue(result.insertedId is BsonObjectId)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findOneDslBlockSendsBuiltFilter() = runTest {
        val found = BsonDocument("title" to BsonString("Kindred"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(BsonDocument("title" to BsonString("Kindred")), find.body["filter"])
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
                val result =
                    client
                        .database("library")
                        .typedCollection<TestBook>("books")
                        .findOne { TestBook::title eq "Kindred" }
                assertEquals(TestBook("Kindred"), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findOneTypedCollectionBlockSendsSerializerMappedFieldName() = runTest {
        val found = BsonDocument("book_title" to BsonString("Dune"))

        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(BsonDocument("book_title" to BsonString("Dune")), find.body["filter"])
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
                val result =
                    client
                        .database("library")
                        .typedCollection<SerialNamedDslBook>("books")
                        .findOne { SerialNamedDslBook::title eq "Dune" }
                assertEquals(SerialNamedDslBook("Dune"), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findDslBlockPreservesOptionsAndSendsBuiltFilter() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(BsonDocument("year" to BsonDocument("\$gte" to BsonInt32(1965))), find.body["filter"])
                assertEquals(BsonInt32(2), find.body["limit"])
                assertEquals(BsonInt32(1), find.body["batchSize"])
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
                        .find(limit = 2, batchSize = 1) {
                            "year" gte 1965
                        }
                        .toList()
                assertEquals(emptyList(), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun findTypedCollectionBlockSendsSerializerMappedFieldName() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val find = receive()
                assertEquals(
                    BsonDocument("published_year" to BsonDocument("\$gte" to BsonInt32(1965))),
                    find.body["filter"]
                )
                assertEquals(BsonInt32(2), find.body["limit"])
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to
                                    BsonArray(
                                        listOf(
                                            BsonDocument(
                                                "book_title" to BsonString("Dune"),
                                                "published_year" to BsonInt32(1965)
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
                val result =
                    client
                        .database("library")
                        .typedCollection<SerialNamedDslBook>("books")
                        .find(limit = 2) {
                            SerialNamedDslBook::year gte 1965
                        }
                        .toList()
                assertEquals(listOf(SerialNamedDslBook("Dune", 1965)), result)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneDslBlocksSendOperatorDocument() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val update = receive()
                val statement = update.body["updates"].asBson<BsonArray>().values.single().asBson<BsonDocument>()
                assertEquals(BsonDocument("title" to BsonString("Dune")), statement["q"])
                assertEquals(
                    BsonDocument("\$set" to BsonDocument("status" to BsonString("published"))),
                    statement["u"]
                )
                reply(
                    update,
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
                    client.database("library").collection("books").updateOne(
                        filter = { "title" eq "Dune" },
                        update = { set("status", "published") }
                    )
                assertEquals(1L, result.modifiedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateManyAcceptsBuiltFilterAndUpdateDslBlock() = runTest {
        val filter = filter { "status" eq "draft" }

        withFakeMongoServer(
            handler = {
                expectHello()
                val update = receive()
                val statement = update.body["updates"].asBson<BsonArray>().values.single().asBson<BsonDocument>()
                assertEquals(filter, statement["q"])
                assertEquals(BsonBoolean(true), statement["multi"])
                assertEquals(
                    BsonDocument("\$inc" to BsonDocument("revision" to BsonInt32(1))),
                    statement["u"]
                )
                reply(
                    update,
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
                    client.database("library").collection("books").updateMany(filter) {
                        inc("revision", 1)
                    }
                assertEquals(2L, result.modifiedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateOneTypedCollectionBlockSendsSerializerMappedUpdateFieldName() = runTest {
        val id = BsonObjectId.fromHex("000000000000000000000001")

        withFakeMongoServer(
            handler = {
                expectHello()
                val update = receive()
                val statement = update.body["updates"].asBson<BsonArray>().values.single().asBson<BsonDocument>()
                assertEquals(BsonDocument("_id" to id), statement["q"])
                assertEquals(
                    BsonDocument("\$set" to BsonDocument("book_title" to BsonString("Dune"))),
                    statement["u"]
                )
                reply(
                    update,
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
                    client.database("library").typedCollection<SerialNamedUpdateDslBook>("books").updateOne(
                        filter = { SerialNamedUpdateDslBook::id eq id },
                        update = { set(SerialNamedUpdateDslBook::title, "Dune") }
                    )
                assertEquals(1L, result.modifiedCount)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun updateManyTypedCollectionBlockSendsSerializerMappedUpdateFieldName() = runTest {
        withFakeMongoServer(
            handler = {
                expectHello()
                val update = receive()
                val statement = update.body["updates"].asBson<BsonArray>().values.single().asBson<BsonDocument>()
                assertEquals(
                    BsonDocument("published_year" to BsonDocument("\$gte" to BsonInt32(1965))),
                    statement["q"]
                )
                assertEquals(BsonBoolean(true), statement["multi"])
                assertEquals(
                    BsonDocument("\$inc" to BsonDocument("published_year" to BsonInt32(1))),
                    statement["u"]
                )
                reply(
                    update,
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
                    client.database("library").typedCollection<SerialNamedUpdateDslBook>("books").updateMany(
                        filter = { SerialNamedUpdateDslBook::year gte 1965 },
                        update = { inc(SerialNamedUpdateDslBook::year, 1) }
                    )
                assertEquals(2L, result.modifiedCount)
            } finally {
                client.close()
            }
        }
    }
}

private inline fun <reified T : BsonValue> BsonValue?.asBson(): T =
    this as? T ?: error("Unexpected BSON value $this")
