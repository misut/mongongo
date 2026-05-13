# mongongo

mongongo is a MongoDB client for Kotlin/Native and Kotlin Multiplatform.

The current implementation is an early BSON-first client. It is useful for
trying basic MongoDB commands from common Kotlin code, but it is not a full
replacement for the official MongoDB drivers yet.

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

Unsupported or limited in this v0 surface:

- sessions and transactions
- connection pooling
- retryable writes
- change streams
- aggregation
- typed serialization
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

### insertOne and findOne

```kotlin
import mongongo.BsonDocument
import mongongo.BsonObjectId
import mongongo.BsonString
import mongongo.MongoClient

suspend fun insertAndFindOne() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").collection("books")
        val insert = collection.insertOne(BsonDocument("title" to BsonString("The Left Hand of Darkness")))
        val id = insert.insertedId as BsonObjectId

        val found = collection.findOne(BsonDocument("_id" to id))
        check(found?.getString("title") == "The Left Hand of Darkness")
    } finally {
        client.close()
    }
}
```

### insertMany and cursor find

```kotlin
import mongongo.BsonDocument
import mongongo.BsonString
import mongongo.MongoClient

suspend fun insertAndFindMany() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").collection("books")
        collection.insertMany(
            listOf(
                BsonDocument("title" to BsonString("Parable of the Sower")),
                BsonDocument("title" to BsonString("A Wizard of Earthsea"))
            )
        )

        val documents = collection.find().toList()
        check(documents.size >= 2)
    } finally {
        client.close()
    }
}
```

### updateOne with `$set`

```kotlin
import mongongo.BsonDocument
import mongongo.BsonObjectId
import mongongo.BsonString
import mongongo.MongoClient

suspend fun updateOneBook() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val collection = client.database("mongongo_example").collection("books")
        val insert = collection.insertOne(BsonDocument("title" to BsonString("Draft"), "status" to BsonString("new")))
        val id = insert.insertedId as BsonObjectId

        collection.updateOne(
            filter = BsonDocument("_id" to id),
            update = BsonDocument("\$set" to BsonDocument("status" to BsonString("published")))
        )

        val found = collection.findOne(BsonDocument("_id" to id))
        check(found?.getString("status") == "published")
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

- The public API works with `BsonDocument` and `BsonValue` types instead of
  typed Kotlin serialization.
- `commonMain` does not depend on the JVM MongoDB driver. The official JVM
  driver is used only in JVM tests for verification.
- Commands are implemented over MongoDB OP_MSG.
- `MongoCursor` supports suspending `next()`, `toList()`, and `close()`. If a
  cursor is not exhausted and you stop reading early, call `close()` to send
  `killCursors`.
- The client uses one connection per `MongoClient` and serializes requests on
  that connection. It does not implement driver-grade topology monitoring or
  pooling yet.

## License

This repository does not currently contain a license file.
