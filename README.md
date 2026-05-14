# mongongo

mongongo is a MongoDB client for Kotlin/Native and Kotlin Multiplatform.

The current implementation is an early v0 client with a BSON-first core and
ergonomic helpers for common CRUD usage. It is useful for trying MongoDB from
common Kotlin code, but it is not a full replacement for the official MongoDB
drivers yet.

## Supported targets

The current Gradle build defines these Kotlin Multiplatform targets:

- JVM
- macOS Arm64

Run `macosArm64Test` on a macOS Arm64 host.

## Feature matrix

| Area | Current support |
| --- | --- |
| Connection strings | `mongodb://`, `mongodb+srv://` |
| Authentication | SCRAM-SHA-256 from URI credentials |
| TLS | `tls=true` and `ssl=true`; SRV URIs enable TLS by default unless disabled |
| CRUD | `insertOne`, `insertMany`, `findOne`, cursor `find`, `deleteOne`, `deleteMany`, `updateOne`, `updateMany`, `replaceOne` |
| Database helpers | `listCollectionNames`, `createCollection`, collection `drop` |
| Index helpers | `createIndex`, `listIndexNames`, `dropIndex` |
| Sessions | explicit `startSession`, `withSession`, and session-bound databases/collections |
| Transactions | explicit `startTransaction`, `withTransaction`, `commit`, and `abort` |
| Typed serialization | kotlinx.serialization v0 for data classes with a small BSON mapping |
| BSON DSLs | filter, update, and document builder helpers for common CRUD calls |

Unsupported or limited in this v0 surface:

- connection pooling
- retryable writes
- transient transaction retries and unknown commit result retries
- causal consistency `operationTime` / `$clusterTime` tracking
- full server session pooling
- change streams
- aggregation
- typed serialization beyond the v0 mapping listed below
- custom TLS CA files and client certificates
- SCRAM-SHA-1
- x509, AWS, GSSAPI, and PLAIN authentication
- SRV polling after connect

## Build from source

Run commands from the repository root so `mise` resolves the repo-local tools:

```sh
mise install
mise exec -- ./gradlew :mongongo-core:check
```

If `mise` reports that the repo config is not trusted, trust only this checkout
or worktree before retrying:

```sh
mise trust .
```

## Connection strings

Plain local MongoDB:

```kotlin
val client = MongoClient.connect("mongodb://127.0.0.1:27017")
```

Authentication with SCRAM-SHA-256:

```kotlin
val client = MongoClient.connect(
    "mongodb://user:p%40ssword@127.0.0.1:27017/app?authSource=admin&authMechanism=SCRAM-SHA-256"
)
```

TLS:

```kotlin
val client = MongoClient.connect("mongodb://db.example.com:27017/app?tls=true")
```

SRV:

```kotlin
val client = MongoClient.connect("mongodb+srv://cluster.example.com/app")
```

MongoDB connection strings use `mongodb://` for standard seed lists and
`mongodb+srv://` for DNS SRV discovery. If credentials are present and
`authSource` is not set, MongoDB authenticates against the path database; if no
path database is present, it uses `admin`. Usernames and passwords that contain
reserved URI characters such as `@`, `/`, or `:` must be percent-encoded.

mongongo supports `SCRAM-SHA-256` only. It rejects other authentication
mechanisms rather than falling back. SRV hosts are resolved when connecting, but
the client does not currently poll SRV records after the initial connection.

Reference:

- [MongoDB connection string formats](https://www.mongodb.com/docs/manual/reference/connection-string-formats/)
- [MongoDB connection string options](https://www.mongodb.com/docs/manual/reference/connection-string-options/)
- [MongoDB SRV polling specification](https://specifications.readthedocs.io/en/latest/polling-srv-records-for-mongos-discovery/polling-srv-records-for-mongos-discovery/)
- [Kotlin Multiplatform Gradle DSL reference](https://kotlinlang.org/docs/multiplatform/multiplatform-dsl-reference.html)
- [Kotlin/Native supported targets and hosts](https://kotlinlang.org/docs/native-target-support.html)

## Basic usage

All client operations are suspending. Close the client when finished.

### BSON-first CRUD with DSL helpers

```kotlin
import mongongo.BsonObjectId
import mongongo.MongoClient

suspend fun insertAndFindOne() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").collection("books")
        val insert =
            collection.insertOne {
                value("title", "The Left Hand of Darkness")
                value("year", 1969)
                value("tags", listOf("sf", "classic"))
            }
        val id = insert.insertedId as BsonObjectId

        val found = collection.findOne { "_id" eq id }
        check(found?.getString("title") == "The Left Hand of Darkness")
    } finally {
        client.close()
    }
}
```

Use `filter { ... }` when a method already takes a `BsonDocument` filter, or pass
a trailing filter block to the collection methods that provide one.

```kotlin
import mongongo.MongoClient
import mongongo.bsonDocument

suspend fun insertAndFindMany() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").collection("books")
        collection.insertMany(
            listOf(
                bsonDocument { value("title", "Parable of the Sower") },
                bsonDocument { value("title", "A Wizard of Earthsea") }
            )
        )

        val documents =
            collection
                .find(limit = 10, batchSize = 5) {
                    "title" inList listOf("Parable of the Sower", "A Wizard of Earthsea")
                }
                .toList()
        check(documents.size >= 2)
    } finally {
        client.close()
    }
}
```

The filter DSL supports equality, `ne`, `gt`, `gte`, `lt`, `lte`, `inList`,
`nin`, `and`, `or`, and document-level `not { ... }`.

### typed serialization v0

`@Serializable` data classes can be used with a typed collection through
kotlinx.serialization. The v0 mapping supports `String`, `Int`, `Long`,
`Double`, `Boolean`, nullable values as BSON null, nested serializable objects,
`List<T>`, and `BsonObjectId`. `@SerialName` controls the BSON field name.

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mongongo.BsonObjectId
import mongongo.MongoClient

@Serializable
data class Book(
    @SerialName("_id")
    val id: BsonObjectId? = null,
    @SerialName("book_title")
    val title: String,
    val status: String = "draft",
    val tags: List<String> = emptyList()
)

suspend fun insertAndFindTypedBook() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").typedCollection<Book>("typed_books")
        collection.insertOne(Book(title = "Dawn", tags = listOf("sf")))
        check(collection.findOne { Book::title eq "Dawn" }?.title == "Dawn")
    } finally {
        client.close()
    }
}
```

For an explicit serializer, use `collection("typed_books", Book.serializer())`.
Polymorphism, maps, enums, byte arrays, dates/datetimes, and other numeric
types are intentionally unsupported in this first mapping and fail with a
serialization exception. Missing default-valued fields decode through the
generated serializer defaults; unknown BSON fields such as MongoDB-generated
`_id` are ignored when the target serializer has no matching property.

Typed property filters on serialization-backed collections resolve field names
through the collection serializer, so `Book::title eq "Dawn"` maps to
`{ "book_title": "Dawn" }` when the property has `@SerialName("book_title")`.
Use `typedFilter<Book> { ... }` when you need a standalone `BsonDocument` with
the same mapping. Raw string filters such as `"title" eq "Dawn"` remain literal
field names. Unsupported property references fail instead of falling back to a
possibly wrong Kotlin property name.

Typed update blocks use the same field-name mapping. Use
`typedUpdate<Book> { set(Book::title, "Dune") }`, or
`update(Book.serializer()) { ... }`, for a
standalone update document. A typed collection can also infer the serializer for
trailing update blocks:

```kotlin
books.updateOne(
    filter = { Book::title eq "Draft" },
    update = { set(Book::title, "Dune") }
)
```

Typed update paths currently cover top-level properties only; nested property
paths remain a raw BSON/string-field escape hatch.

### update DSL

```kotlin
import mongongo.BsonObjectId
import mongongo.MongoClient
import mongongo.filter
import mongongo.update

suspend fun updateOneBook() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").collection("books")
        val insert =
            collection.insertOne {
                value("title", "Draft")
                value("status", "new")
            }
        val id = insert.insertedId as BsonObjectId

        collection.updateOne(
            filter { "_id" eq id },
            update {
                set("status", "published")
                inc("revision", 1)
                addToSet("tags", "released")
            }
        )

        val found = collection.findOne { "_id" eq id }
        check(found?.getString("status") == "published")
    } finally {
        client.close()
    }
}
```

Update DSL blocks always create operator update documents such as
`{ "$set": ... }`. Replacement writes remain explicit through `replaceOne` and
still reject operator documents.

### Sessions and transactions with typed collections

```kotlin
import kotlinx.serialization.Serializable
import mongongo.MongoClient

@Serializable
data class TransactionBook(
    val title: String,
    val status: String = "draft"
)

suspend fun publishInTransaction() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017/?replicaSet=rs0")
    try {
        client.withSession {
            val sessionBooks =
                database("mongongo_example").typedCollection<TransactionBook>("session_books")
            sessionBooks.insertOne(TransactionBook(title = "Kindred"))
            check(sessionBooks.findOne { TransactionBook::title eq "Kindred" } != null)
        }

        client.withTransaction {
            val books = database("mongongo_example").typedCollection<TransactionBook>("transaction_books")
            books.insertOne(TransactionBook(title = "The Dispossessed"))
            books.updateOne(
                filter = { TransactionBook::title eq "The Dispossessed" },
                update = { set("status", "published") }
            )
        }
    } finally {
        client.close()
    }
}
```

### Raw BSON escape hatch

The DSLs are convenience helpers. The stable escape hatch is still direct
`BsonDocument` / `BsonValue` usage for commands or operators that do not have a
dedicated helper yet.

```kotlin
import mongongo.BsonDocument
import mongongo.BsonString
import mongongo.MongoClient

suspend fun rawBsonFind() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").collection("books")
        val found =
            collection.findOne(
                BsonDocument(
                    "title" to
                        BsonDocument(
                            "\$regex" to BsonString("^Dune"),
                            "\$options" to BsonString("i")
                        )
                )
            )
        check(found?.getString("title")?.startsWith("Dune") != false)
    } finally {
        client.close()
    }
}
```

### Index helpers

```kotlin
import mongongo.BsonDocument
import mongongo.BsonInt32
import mongongo.MongoClient

suspend fun createListAndDropIndex() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").collection("books")
        val indexName = collection.createIndex(BsonDocument("title" to BsonInt32(1)))

        check(indexName in collection.listIndexNames())
        collection.dropIndex(indexName)
    } finally {
        client.close()
    }
}
```

## Smoke tests

The normal verification suite uses fake OP_MSG servers and embedded JVM MongoDB
where possible. Native smoke tests against a real MongoDB deployment are
environment-gated and skip when the relevant URI is unset.

Plain local MongoDB smoke:

```sh
MONGONGO_TEST_URI=mongodb://127.0.0.1:27017 mise exec -- ./gradlew :mongongo-core:macosArm64Test --rerun-tasks
```

Optional smoke URIs used by tests:

- `MONGONGO_AUTH_TEST_URI`
- `MONGONGO_TLS_TEST_URI`
- `MONGONGO_AUTH_TLS_TEST_URI`
- `MONGONGO_SRV_TEST_URI`
- `MONGONGO_AUTH_SRV_TEST_URI`

Example:

```sh
MONGONGO_AUTH_TEST_URI='mongodb://user:p%40ssword@127.0.0.1:27017/app?authSource=admin' \
    mise exec -- ./gradlew :mongongo-core:macosArm64Test --rerun-tasks
```

## Design notes

- The default public API works with `BsonDocument` and `BsonValue` types.
  The filter and update DSLs are additive helpers that produce ordinary BSON
  documents; raw BSON remains the escape hatch for unsupported operators.
- Typed `MongoCollection<T>` values can use either an explicit `MongoCodec<T>` or
  the kotlinx.serialization BSON codec v0 for supported `@Serializable` data
  classes. Typed property filters currently use Kotlin property names, not
  serializer field-name mapping.
- `commonMain` does not depend on the JVM MongoDB driver. The official JVM
  driver is used only in JVM tests for verification.
- Commands are implemented over MongoDB OP_MSG.
- `MongoCursor` supports suspending `next()`, `toList()`, and `close()`. If a
  cursor is not exhausted and you stop reading early, call `close()` to send
  `killCursors`.
- The client uses one connection per `MongoClient` and serializes requests on
  that connection. It does not implement driver-grade topology monitoring or
  pooling yet.
- `withTransaction` commits when the block completes and aborts when the block
  throws, then rethrows the original exception. This v0 implementation does not
  retry `TransientTransactionError` or `UnknownTransactionCommitResult` yet.
- Sessions send `lsid` with session-bound commands. Transactions send `lsid`,
  `txnNumber`, and `autocommit: false`; the first transaction operation also
  sends `startTransaction: true`.
- Causal consistency bookkeeping is not implemented yet: the client does not
  track `operationTime`, gossip `$clusterTime`, or add `readConcern.afterClusterTime`.

## License

This repository does not currently contain a license file.
