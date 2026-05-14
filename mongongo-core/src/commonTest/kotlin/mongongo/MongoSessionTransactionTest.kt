package mongongo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
private data class SessionTypedBook(
    @SerialName("book_title")
    val title: String,
    val status: String = "draft"
)

class MongoSessionTransactionTest {
    @Test
    fun startSessionSendsCommandAndEndsSession() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val session = client.startSession()
                session.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun sessionBoundInsertSendsLsid() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val insert = receive()
                assertEquals(listOf("insert", "documents", "ordered", "\$db", "lsid"), insert.body.values.keys.toList())
                assertEquals(BsonString("books"), insert.body["insert"])
                assertEquals(BsonString("library"), insert.body["\$db"])
                assertEquals(lsid, insert.body["lsid"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                client.withSession {
                    database("library")
                        .collection("books")
                        .insertOne(BsonDocument("name" to BsonString("Ada")))
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun endedCleanSessionsAreReusedAndEndedWhenClientCloses() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val firstInsert = receive()
                assertEquals(lsid, firstInsert.body["lsid"])
                reply(firstInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val secondInsert = receive()
                assertEquals(lsid, secondInsert.body["lsid"])
                reply(secondInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val firstSession = client.startSession()
                firstSession
                    .database("library")
                    .collection("books")
                    .insertOne(BsonDocument("name" to BsonString("Ada")))
                firstSession.close()

                val secondSession = client.startSession()
                secondSession
                    .database("library")
                    .collection("books")
                    .insertOne(BsonDocument("name" to BsonString("Grace")))
                secondSession.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun reusedSessionKeepsIncreasingTransactionNumbers() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val firstInsert = receive()
                assertEquals(lsid, firstInsert.body["lsid"])
                assertEquals(BsonInt64(1), firstInsert.body["txnNumber"])
                assertEquals(BsonBoolean(true), firstInsert.body["startTransaction"])
                reply(firstInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
                expectCommit(lsid, txnNumber = 1)

                val secondInsert = receive()
                assertEquals(lsid, secondInsert.body["lsid"])
                assertEquals(BsonInt64(2), secondInsert.body["txnNumber"])
                assertEquals(BsonBoolean(true), secondInsert.body["startTransaction"])
                reply(secondInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
                expectCommit(lsid, txnNumber = 2)

                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                client.withSession {
                    withTransaction {
                        database("library")
                            .collection("books")
                            .insertOne(BsonDocument("name" to BsonString("Ada")))
                    }
                }

                client.withSession {
                    withTransaction {
                        database("library")
                            .collection("books")
                            .insertOne(BsonDocument("name" to BsonString("Grace")))
                    }
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun dirtySessionsAreNotReused() = runTest {
        val dirtyLsid = testSessionId()
        val cleanLsid =
            BsonDocument(
                "id" to
                    BsonBinary(
                        subtype = 4,
                        bytes = (16 until 32).map { it.toByte() }
                    )
            )

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(dirtyLsid)

                val insert = receive()
                assertEquals(dirtyLsid, insert.body["lsid"])
                assertEquals(BsonBoolean(true), insert.body["startTransaction"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val abort = receive()
                assertEquals(BsonInt32(1), abort.body["abortTransaction"])
                assertEquals(dirtyLsid, abort.body["lsid"])
                reply(
                    abort,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(91),
                        "errmsg" to BsonString("abort failed")
                    )
                )

                expectEndSessions(dirtyLsid)
                expectStartSession(cleanLsid)

                val cleanInsert = receive()
                assertEquals(cleanLsid, cleanInsert.body["lsid"])
                reply(cleanInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                expectEndSessions(cleanLsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val dirtySession = client.startSession()
                dirtySession
                    .startTransaction()
                    .database("library")
                    .collection("books")
                    .insertOne(BsonDocument("name" to BsonString("Ada")))
                dirtySession.close()

                val cleanSession = client.startSession()
                cleanSession
                    .database("library")
                    .collection("books")
                    .insertOne(BsonDocument("name" to BsonString("Grace")))
                cleanSession.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun expiredSessionsAreNotReused() = runTest {
        val expiredLsid = testSessionId()
        val freshLsid =
            BsonDocument(
                "id" to
                    BsonBinary(
                        subtype = 4,
                        bytes = (32 until 48).map { it.toByte() }
                    )
            )

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(expiredLsid, timeoutMinutes = 0)

                val firstInsert = receive()
                assertEquals(expiredLsid, firstInsert.body["lsid"])
                reply(firstInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                expectEndSessions(expiredLsid)
                expectStartSession(freshLsid)

                val secondInsert = receive()
                assertEquals(freshLsid, secondInsert.body["lsid"])
                reply(secondInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                expectEndSessions(freshLsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val expiredSession = client.startSession()
                expiredSession
                    .database("library")
                    .collection("books")
                    .insertOne(BsonDocument("name" to BsonString("Ada")))
                expiredSession.close()

                val freshSession = client.startSession()
                freshSession
                    .database("library")
                    .collection("books")
                    .insertOne(BsonDocument("name" to BsonString("Grace")))
                freshSession.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun transactionAddsFieldsToFirstAndSubsequentOperationsAndCommits() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val insert = receive()
                assertEquals(
                    listOf("insert", "documents", "ordered", "\$db", "lsid", "txnNumber", "startTransaction", "autocommit"),
                    insert.body.values.keys.toList()
                )
                assertEquals(BsonString("books"), insert.body["insert"])
                assertEquals(BsonString("library"), insert.body["\$db"])
                assertEquals(lsid, insert.body["lsid"])
                assertEquals(BsonInt64(1), insert.body["txnNumber"])
                assertEquals(BsonBoolean(true), insert.body["startTransaction"])
                assertEquals(BsonBoolean(false), insert.body["autocommit"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val find = receive()
                assertEquals(
                    listOf("find", "filter", "limit", "singleBatch", "\$db", "lsid", "txnNumber", "autocommit"),
                    find.body.values.keys.toList()
                )
                assertEquals(lsid, find.body["lsid"])
                assertEquals(BsonInt64(1), find.body["txnNumber"])
                assertEquals(null, find.body["startTransaction"])
                assertEquals(BsonBoolean(false), find.body["autocommit"])
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

                expectCommit(lsid, txnNumber = 1)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                client.withTransaction {
                    val collection = database("library").collection("books")
                    collection.insertOne(BsonDocument("name" to BsonString("Ada")))
                    collection.findOne(BsonDocument("name" to BsonString("Ada")))
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun transactionCursorUsesSameContextForGetMore() = runTest {
        val lsid = testSessionId()
        val first = BsonDocument("name" to BsonString("Ada"))
        val second = BsonDocument("name" to BsonString("Grace"))

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val find = receive()
                assertEquals(BsonString("books"), find.body["find"])
                assertEquals(lsid, find.body["lsid"])
                assertEquals(BsonInt64(1), find.body["txnNumber"])
                assertEquals(BsonBoolean(true), find.body["startTransaction"])
                assertEquals(BsonBoolean(false), find.body["autocommit"])
                reply(
                    find,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(77),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to BsonArray(listOf(first))
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val getMore = receive()
                assertEquals(
                    listOf("getMore", "collection", "\$db", "lsid", "txnNumber", "autocommit"),
                    getMore.body.values.keys.toList()
                )
                assertEquals(BsonInt64(77), getMore.body["getMore"])
                assertEquals(lsid, getMore.body["lsid"])
                assertEquals(BsonInt64(1), getMore.body["txnNumber"])
                assertEquals(null, getMore.body["startTransaction"])
                assertEquals(BsonBoolean(false), getMore.body["autocommit"])
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

                expectCommit(lsid, txnNumber = 1)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val documents =
                    client.withTransaction {
                        database("library")
                            .collection("books")
                            .find(batchSize = 1)
                            .toList()
                    }
                assertEquals(listOf(first, second), documents)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun transactionBoundTypedCollectionPreservesContextFields() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val insert = receive()
                assertEquals(
                    listOf("insert", "documents", "ordered", "\$db", "lsid", "txnNumber", "startTransaction", "autocommit"),
                    insert.body.values.keys.toList()
                )
                assertEquals(BsonString("books"), insert.body["insert"])
                assertEquals(BsonString("library"), insert.body["\$db"])
                assertEquals(lsid, insert.body["lsid"])
                assertEquals(BsonInt64(1), insert.body["txnNumber"])
                assertEquals(BsonBoolean(true), insert.body["startTransaction"])
                assertEquals(BsonBoolean(false), insert.body["autocommit"])
                val document =
                    (insert.body["documents"] as BsonArray)
                        .values
                        .single() as BsonDocument
                assertEquals(BsonString("Kindred"), document["title"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val find = receive()
                assertEquals(
                    listOf("find", "filter", "limit", "singleBatch", "\$db", "lsid", "txnNumber", "autocommit"),
                    find.body.values.keys.toList()
                )
                assertEquals(BsonString("books"), find.body["find"])
                assertEquals(lsid, find.body["lsid"])
                assertEquals(BsonInt64(1), find.body["txnNumber"])
                assertEquals(null, find.body["startTransaction"])
                assertEquals(BsonBoolean(false), find.body["autocommit"])
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

                expectCommit(lsid, txnNumber = 1)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val found =
                    client.withTransaction {
                        val collection = database("library").typedCollection<TestBook>("books")
                        collection.insertOne(TestBook("Kindred"))
                        collection.findOne { TestBook::title eq "Kindred" }
                    }
                assertEquals(TestBook("Kindred"), found)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun sessionAndSessionTransactionBoundTypedCollectionsPreserveContextAndSerializerFields() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val sessionInsert = receive()
                assertEquals(listOf("insert", "documents", "ordered", "\$db", "lsid"), sessionInsert.body.values.keys.toList())
                assertEquals(lsid, sessionInsert.body["lsid"])
                val inserted =
                    (sessionInsert.body["documents"] as BsonArray)
                        .values
                        .single() as BsonDocument
                assertEquals(BsonString("Session Book"), inserted["book_title"])
                reply(sessionInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val sessionFind = receive()
                assertEquals(listOf("find", "filter", "limit", "singleBatch", "\$db", "lsid"), sessionFind.body.values.keys.toList())
                assertEquals(lsid, sessionFind.body["lsid"])
                assertEquals(BsonDocument("book_title" to BsonString("Session Book")), sessionFind.body["filter"])
                reply(
                    sessionFind,
                    BsonDocument(
                        "cursor" to
                            BsonDocument(
                                "id" to BsonInt64(0),
                                "ns" to BsonString("library.books"),
                                "firstBatch" to
                                    BsonArray(
                                        listOf(BsonDocument("book_title" to BsonString("Session Book")))
                                    )
                            ),
                        "ok" to BsonDouble(1.0)
                    )
                )

                val transactionUpdate = receive()
                assertEquals(
                    listOf("update", "updates", "ordered", "\$db", "lsid", "txnNumber", "startTransaction", "autocommit"),
                    transactionUpdate.body.values.keys.toList()
                )
                assertEquals(lsid, transactionUpdate.body["lsid"])
                assertEquals(BsonInt64(1), transactionUpdate.body["txnNumber"])
                assertEquals(BsonBoolean(true), transactionUpdate.body["startTransaction"])
                assertEquals(BsonBoolean(false), transactionUpdate.body["autocommit"])
                val statement =
                    (transactionUpdate.body["updates"] as BsonArray)
                        .values
                        .single() as BsonDocument
                assertEquals(BsonDocument("book_title" to BsonString("Session Book")), statement["q"])
                assertEquals(BsonDocument("\$set" to BsonDocument("status" to BsonString("published"))), statement["u"])
                reply(
                    transactionUpdate,
                    BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1), "nModified" to BsonInt32(1))
                )

                expectCommit(lsid, txnNumber = 1)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                client.withSession {
                    val sessionBooks = database("library").typedCollection<SessionTypedBook>("books")
                    sessionBooks.insertOne(SessionTypedBook("Session Book"))
                    assertEquals(SessionTypedBook("Session Book"), sessionBooks.findOne { SessionTypedBook::title eq "Session Book" })

                    withTransaction {
                        val transactionBooks = database("library").typedCollection<SessionTypedBook>("books")
                        transactionBooks.updateOne(
                            filter = { SessionTypedBook::title eq "Session Book" },
                            update = { set(SessionTypedBook::status, "published") }
                        )
                    }
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun withTransactionAbortsAndRethrowsOriginalException() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val insert = receive()
                assertEquals(BsonBoolean(true), insert.body["startTransaction"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                expectAbort(lsid, txnNumber = 1)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val failure =
                    assertFailsWith<IllegalStateException> {
                        client.withTransaction {
                            database("library")
                                .collection("books")
                                .insertOne(BsonDocument("name" to BsonString("Ada")))
                            error("boom")
                        }
                    }
                assertEquals("boom", failure.message)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun withTransactionDoesNotAbortAfterCommitFailure() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val insert = receive()
                assertEquals(BsonBoolean(true), insert.body["startTransaction"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val commit = receive()
                assertEquals(BsonInt32(1), commit.body["commitTransaction"])
                assertEquals(lsid, commit.body["lsid"])
                assertEquals(BsonInt64(1), commit.body["txnNumber"])
                assertEquals(BsonBoolean(false), commit.body["autocommit"])
                reply(
                    commit,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(251),
                        "errmsg" to BsonString("commit outcome unknown")
                    )
                )

                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                assertFailsWith<MongoCommandException> {
                    client.withTransaction {
                        database("library")
                            .collection("books")
                            .insertOne(BsonDocument("name" to BsonString("Ada")))
                    }
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun withTransactionRetriesWholeTransactionForTransientTransactionError() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val firstInsert = receive()
                assertEquals(BsonBoolean(true), firstInsert.body["startTransaction"])
                assertEquals(BsonInt64(1), firstInsert.body["txnNumber"])
                reply(
                    firstInsert,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(112),
                        "errmsg" to BsonString("write conflict"),
                        "errorLabels" to BsonArray(listOf(BsonString("TransientTransactionError")))
                    )
                )

                expectAbort(lsid, txnNumber = 1)

                val secondInsert = receive()
                assertEquals(BsonBoolean(true), secondInsert.body["startTransaction"])
                assertEquals(BsonInt64(2), secondInsert.body["txnNumber"])
                reply(secondInsert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                expectCommit(lsid, txnNumber = 2)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                var attempts = 0
                client.withTransaction {
                    attempts += 1
                    database("library")
                        .collection("books")
                        .insertOne(BsonDocument("name" to BsonString("Ada")))
                }
                assertEquals(2, attempts)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun withTransactionRetriesCommitForUnknownTransactionCommitResult() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val insert = receive()
                assertEquals(BsonBoolean(true), insert.body["startTransaction"])
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))

                val firstCommit = receive()
                assertEquals(BsonInt32(1), firstCommit.body["commitTransaction"])
                assertEquals(BsonInt64(1), firstCommit.body["txnNumber"])
                reply(
                    firstCommit,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(64),
                        "errmsg" to BsonString("commit result unknown"),
                        "errorLabels" to BsonArray(listOf(BsonString("UnknownTransactionCommitResult")))
                    )
                )

                val secondCommit = receive()
                assertEquals(BsonInt32(1), secondCommit.body["commitTransaction"])
                assertEquals(BsonInt64(1), secondCommit.body["txnNumber"])
                reply(secondCommit, BsonDocument("ok" to BsonDouble(1.0)))

                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                var attempts = 0
                client.withTransaction {
                    attempts += 1
                    database("library")
                        .collection("books")
                        .insertOne(BsonDocument("name" to BsonString("Ada")))
                }
                assertEquals(1, attempts)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun withTransactionDoesNotRetryNonLabeledCommandError() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val insert = receive()
                assertEquals(BsonBoolean(true), insert.body["startTransaction"])
                reply(
                    insert,
                    BsonDocument(
                        "ok" to BsonDouble(0.0),
                        "code" to BsonInt32(13),
                        "errmsg" to BsonString("not authorized")
                    )
                )

                expectAbort(lsid, txnNumber = 1)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                var attempts = 0
                val failure =
                    assertFailsWith<MongoCommandException> {
                        client.withTransaction {
                            attempts += 1
                            database("library")
                                .collection("books")
                                .insertOne(BsonDocument("name" to BsonString("Ada")))
                        }
                    }
                assertEquals(1, attempts)
                assertEquals(13, failure.code)
                assertEquals(emptySet(), failure.errorLabels)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun endedSessionAndCompletedTransactionRejectOperations() = runTest {
        val lsid = testSessionId()

        withFakeMongoServer(
            handler = {
                expectHello()
                expectStartSession(lsid)

                val insert = receive()
                reply(insert, BsonDocument("ok" to BsonDouble(1.0), "n" to BsonInt32(1)))
                expectCommit(lsid, txnNumber = 1)
                expectEndSessions(lsid)
            }
        ) { uri ->
            val client = MongoClient.connect(uri)
            try {
                val session = client.startSession()
                val closedSessionCollection = session.database("library").collection("closed")
                val transaction = session.startTransaction()
                val completedTransactionCollection = transaction.database("library").collection("books")

                completedTransactionCollection.insertOne(BsonDocument("name" to BsonString("Ada")))
                transaction.commit()

                assertFailsWith<IllegalStateException> {
                    completedTransactionCollection.findOne()
                }

                session.close()

                assertFailsWith<IllegalStateException> {
                    closedSessionCollection.findOne()
                }
            } finally {
                client.close()
            }
        }
    }
}

private suspend fun FakeMongoConnection.expectStartSession(lsid: BsonDocument, timeoutMinutes: Int = 30) {
    val startSession = receive()
    assertEquals(listOf("startSession", "\$db"), startSession.body.values.keys.toList())
    assertEquals(BsonInt32(1), startSession.body["startSession"])
    assertEquals(BsonString("admin"), startSession.body["\$db"])
    reply(
        startSession,
        BsonDocument(
            "id" to lsid,
            "timeoutMinutes" to BsonInt32(timeoutMinutes),
            "ok" to BsonDouble(1.0)
        )
    )
}

private suspend fun FakeMongoConnection.expectCommit(lsid: BsonDocument, txnNumber: Long) {
    val commit = receive()
    assertEquals(listOf("commitTransaction", "\$db", "lsid", "txnNumber", "autocommit"), commit.body.values.keys.toList())
    assertEquals(BsonInt32(1), commit.body["commitTransaction"])
    assertEquals(BsonString("admin"), commit.body["\$db"])
    assertEquals(lsid, commit.body["lsid"])
    assertEquals(BsonInt64(txnNumber), commit.body["txnNumber"])
    assertEquals(BsonBoolean(false), commit.body["autocommit"])
    reply(commit, BsonDocument("ok" to BsonDouble(1.0)))
}

private suspend fun FakeMongoConnection.expectAbort(lsid: BsonDocument, txnNumber: Long) {
    val abort = receive()
    assertEquals(listOf("abortTransaction", "\$db", "lsid", "txnNumber", "autocommit"), abort.body.values.keys.toList())
    assertEquals(BsonInt32(1), abort.body["abortTransaction"])
    assertEquals(BsonString("admin"), abort.body["\$db"])
    assertEquals(lsid, abort.body["lsid"])
    assertEquals(BsonInt64(txnNumber), abort.body["txnNumber"])
    assertEquals(BsonBoolean(false), abort.body["autocommit"])
    reply(abort, BsonDocument("ok" to BsonDouble(1.0)))
}

private suspend fun FakeMongoConnection.expectEndSessions(lsid: BsonDocument) {
    val endSessions = receive()
    assertEquals(listOf("endSessions", "\$db"), endSessions.body.values.keys.toList())
    assertEquals(BsonString("admin"), endSessions.body["\$db"])
    assertEquals(BsonArray(listOf(lsid)), endSessions.body["endSessions"])
    reply(endSessions, BsonDocument("ok" to BsonDouble(1.0)))
}

private fun testSessionId(): BsonDocument =
    BsonDocument(
        "id" to
            BsonBinary(
                subtype = 4,
                bytes = (0 until 16).map { it.toByte() }
            )
    )
