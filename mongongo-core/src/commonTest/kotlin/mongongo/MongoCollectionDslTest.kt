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

@Serializable
private data class TypedCrudBook(
    @SerialName("_id")
    val id: BsonObjectId,
    @SerialName("book_title")
    val title: String,
    @SerialName("author_name")
    val author: String,
    val revision: Int = 0,
    val status: String = "draft"
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

    @Test
    fun typedCollectionCrudHappyPathSendsSerializerAwareCommandBodies() = runTest {
        val firstId = BsonObjectId.fromHex("000000000000000000000101")
        val secondId = BsonObjectId.fromHex("000000000000000000000102")
        val thirdId = BsonObjectId.fromHex("000000000000000000000103")

        withFakeMongoServer(
            handler = {
                expectHello()

                val insertOne = receive()
                val inserted = insertOne.singleDocument("documents")
                assertEquals(BsonString("books"), insertOne.body["insert"])
                assertEquals(BsonString("Dune"), inserted["book_title"])
                assertEquals(BsonString("Frank Herbert"), inserted["author_name"])
                assertEquals(BsonInt32(1), inserted["revision"])
                reply(insertOne, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val insertMany = receive()
                val insertedMany = insertMany.body["documents"].asBson<BsonArray>().values.map { it.asBson<BsonDocument>() }
                assertEquals(listOf(secondId, thirdId), insertedMany.map { it["_id"] })
                assertEquals(listOf(BsonString("Octavia Butler"), BsonString("Octavia Butler")), insertedMany.map { it["author_name"] })
                reply(insertMany, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(2)))

                val findOne = receive()
                assertEquals(BsonDocument("_id" to firstId), findOne.body["filter"])
                reply(
                    findOne,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(listOf(inserted))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val find = receive()
                assertEquals(BsonDocument("author_name" to BsonString("Octavia Butler")), find.body["filter"])
                assertEquals(BsonInt32(10), find.body["limit"])
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(insertedMany)
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val updateOne = receive()
                val updateOneStatement = updateOne.singleDocument("updates")
                assertEquals(BsonDocument("_id" to firstId), updateOneStatement["q"])
                assertEquals(BsonDocument("\$set" to BsonDocument("book_title" to BsonString("Dune Messiah"))), updateOneStatement["u"])
                reply(updateOne, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1), "nModified" to BsonInt32(1)))

                val updateMany = receive()
                val updateManyStatement = updateMany.singleDocument("updates")
                assertEquals(BsonDocument("author_name" to BsonString("Octavia Butler")), updateManyStatement["q"])
                assertEquals(BsonBoolean(true), updateManyStatement["multi"])
                assertEquals(BsonDocument("\$inc" to BsonDocument("revision" to BsonInt32(1))), updateManyStatement["u"])
                reply(updateMany, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(2), "nModified" to BsonInt32(2)))

                val replaceOne = receive()
                val replaceStatement = replaceOne.singleDocument("updates")
                assertEquals(BsonDocument("_id" to firstId), replaceStatement["q"])
                assertEquals(
                    BsonDocument(
                        "_id" to firstId,
                        "book_title" to BsonString("Children of Dune"),
                        "author_name" to BsonString("Frank Herbert"),
                        "revision" to BsonInt32(2),
                        "status" to BsonString("published")
                    ),
                    replaceStatement["u"]
                )
                reply(replaceOne, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1), "nModified" to BsonInt32(1)))

                val deleteOne = receive()
                val deleteOneStatement = deleteOne.singleDocument("deletes")
                assertEquals(BsonDocument("_id" to firstId), deleteOneStatement["q"])
                assertEquals(BsonInt32(1), deleteOneStatement["limit"])
                reply(deleteOne, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val deleteMany = receive()
                val deleteManyStatement = deleteMany.singleDocument("deletes")
                assertEquals(BsonDocument("status" to BsonString("archived")), deleteManyStatement["q"])
                assertEquals(BsonInt32(0), deleteManyStatement["limit"])
                reply(deleteMany, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(2)))
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val books = client.database("library").typedCollection<TypedCrudBook>("books")
                books.insertOne(TypedCrudBook(firstId, "Dune", "Frank Herbert", revision = 1))
                books.insertMany(
                    listOf(
                        TypedCrudBook(secondId, "Kindred", "Octavia Butler"),
                        TypedCrudBook(thirdId, "Dawn", "Octavia Butler")
                    )
                )

                assertEquals(TypedCrudBook(firstId, "Dune", "Frank Herbert", revision = 1), books.findOne { TypedCrudBook::id eq firstId })
                assertEquals(
                    listOf(
                        TypedCrudBook(secondId, "Kindred", "Octavia Butler"),
                        TypedCrudBook(thirdId, "Dawn", "Octavia Butler")
                    ),
                    books.find(limit = 10) { TypedCrudBook::author eq "Octavia Butler" }.toList()
                )

                books.updateOne(
                    filter = { TypedCrudBook::id eq firstId },
                    update = { set(TypedCrudBook::title, "Dune Messiah") }
                )
                books.updateMany(
                    filter = { TypedCrudBook::author eq "Octavia Butler" },
                    update = { inc(TypedCrudBook::revision, 1) }
                )
                books.replaceOne(
                    filter = { TypedCrudBook::id eq firstId },
                    replacement =
                        TypedCrudBook(
                            id = firstId,
                            title = "Children of Dune",
                            author = "Frank Herbert",
                            revision = 2,
                            status = "published"
                        )
                )
                books.deleteOne { TypedCrudBook::id eq firstId }
                books.deleteMany { TypedCrudBook::status eq "archived" }
            } finally {
                client.close()
            }
        }
    }
}

private inline fun <reified T : BsonValue> BsonValue?.asBson(): T =
    this as? T ?: error("Unexpected BSON value $this")

private fun OpMsgFrame.singleDocument(name: String): BsonDocument =
    body[name]
        .asBson<BsonArray>()
        .values
        .single()
        .asBson()
