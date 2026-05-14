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
| Error metadata | command error `code`, `codeName`, `errmsg`, and `errorLabels` |
| Typed serialization | kotlinx.serialization v0 for data classes with a small BSON mapping |
| BSON DSLs | filter, update, and document builder helpers for common CRUD calls |

Unsupported or limited in this v0 surface:

- connection pooling
- retryable writes
- causal consistency `operationTime` / `$clusterTime` tracking
- change streams
- aggregation
- typed serialization beyond the v0 mapping listed below
- custom TLS CA files and client certificates
- SCRAM-SHA-1
- x509, AWS, GSSAPI, and PLAIN authentication
- SRV polling after connect

## Install

mongongo v0.1.0 publishes the Kotlin Multiplatform root artifact:

```kotlin
repositories {
    mavenCentral()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.misut:mongongo-core:0.1.0")
        }
    }
}
```

The root coordinate resolves to the matching target artifact through Gradle
metadata. The current release publishes:

- `io.github.misut:mongongo-core`
- `io.github.misut:mongongo-core-jvm`
- `io.github.misut:mongongo-core-macosarm64`

## Build from source

Run commands from the repository root so `mise` resolves the repo-local tools:

```sh
mise install
mise exec -- ./gradlew :mongongo-core:check
mise exec -- ./gradlew :mongongo-core:publishToMavenLocal
```

If `mise` reports that the repo config is not trusted, trust only this checkout
or worktree before retrying:

```sh
mise trust .
```

Local release validation uses the same target set as CI:

```sh
mise exec -- ./gradlew :mongongo-core:spotlessCheck :mongongo-core:jvmTest :mongongo-core:macosArm64Test
mise exec -- ./gradlew :mongongo-core:check
mise exec -- ./gradlew :mongongo-core:publishToMavenLocal
git diff --check
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

### Typed collection happy path

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mongongo.BsonObjectId
import mongongo.MongoClient

@Serializable
data class Book(
    @SerialName("_id")
    val id: BsonObjectId,
    @SerialName("book_title")
    val title: String,
    val author: String,
    val revision: Int = 0,
    val status: String = "draft",
    val tags: List<String> = emptyList()
)

suspend fun typedCrud() {
    val client = MongoClient.connect("mongodb://127.0.0.1:27017")
    try {
        val books = client.database("mongongo_example").typedCollection<Book>("books")
        val id = BsonObjectId.fromHex("00112233445566778899aabb")

        books.insertOne(Book(id = id, title = "Dune", author = "Frank Herbert"))
        books.insertMany(listOf(Book(BsonObjectId.fromHex("00112233445566778899aabc"), "Dawn", "Octavia Butler")))

        check(books.findOne { Book::id eq id }?.title == "Dune")
        check(books.find(limit = 10) { Book::author eq "Octavia Butler" }.toList().isNotEmpty())

        books.updateOne(filter = { Book::id eq id }, update = { set(Book::title, "Dune Messiah") })
        books.updateMany(filter = { Book::author eq "Octavia Butler" }, update = { inc(Book::revision, 1) })
        books.replaceOne(filter = { Book::id eq id }, replacement = Book(id, "Children of Dune", "Frank Herbert"))
        books.deleteOne { Book::id eq id }
        books.deleteMany { Book::status eq "archived" }
    } finally {
        client.close()
    }
}
```

For an explicit serializer, use `collection("books", Book.serializer())`.
Typed property filters and updates resolve field names through the serializer,
so `Book::title eq "Dune"` maps to `{ "book_title": "Dune" }` when the property
has `@SerialName("book_title")`.

The filter DSL supports equality, `ne`, `gt`, `gte`, `lt`, `lte`, `inList`,
`nin`, `and`, `or`, and document-level `not { ... }`. Use
`typedFilter<Book> { ... }` or `typedUpdate<Book> { ... }` when you need a
standalone `BsonDocument` with serializer-aware field names.

The v0 mapping supports `String`, `Int`, `Long`, `Double`, `Boolean`, nullable
values as BSON null, nested serializable objects, `List<T>`, and `BsonObjectId`.
Missing default-valued fields decode through generated serializer defaults;
missing nullable fields decode as `null`; unknown BSON fields are ignored.
Polymorphism, maps, enums, byte arrays, dates/datetimes, and other numeric types
are intentionally unsupported and fail with a serialization exception.

### Explicit nested field paths

Nested `KProperty` paths are not supported yet. Use explicit BSON field paths
when targeting nested fields:

```kotlin
books.findOne { field("metadata.edition") eq 2 }
books.updateOne(
    filter = { Book::id eq id },
    update = { set(field("metadata.edition"), 3) }
)
```

Explicit `field("a.b")` paths are literal BSON paths and work in typed and
BSON-first filter/update DSL contexts.

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
                update = { set(TransactionBook::status, "published") }
            )
        }
    } finally {
        client.close()
    }
}
```

### BSON-first CRUD with DSL helpers

The BSON-first API remains the stable escape hatch. Use it for unsupported
serialization mappings, operators, and command shapes.

```kotlin
import mongongo.BsonObjectId
import mongongo.MongoClient

suspend fun bsonCrud() {
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

        collection.updateOne(filter = { "_id" eq id }, update = { set("status", "published") })
        check(collection.findOne { "_id" eq id }?.getString("title") == "The Left Hand of Darkness")
    } finally {
        client.close()
    }
}
```

Update DSL blocks always create operator update documents such as
`{ "$set": ... }`. Replacement writes remain explicit through `replaceOne` and
still reject operator documents.

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

### MongoDB errors and labels

Command errors expose MongoDB metadata through `MongoCommandException`.
Write-command failures use `MongoWriteException`, authentication failures use
`MongoAuthenticationException`, and both preserve the command metadata when the
server provided it.

```kotlin
try {
    client.database("mongongo_example").collection("books").insertOne {
        value("_id", "known-id")
        value("title", "Duplicate")
    }
} catch (exception: MongoCommandException) {
    val code = exception.code
    val codeName = exception.codeName
    val message = exception.errmsg

    if (exception.hasErrorLabel("TransientTransactionError")) {
        // The built-in withTransaction helper handles this label with bounded retries.
    }
}
```

Error labels are exposed for transaction retry decisions. The v0
`withTransaction` helper uses only MongoDB server-provided labels and a small
fixed retry bound; manual transaction APIs remain single-attempt.

## Smoke tests

The normal verification suite uses fake OP_MSG servers and embedded JVM MongoDB
where possible. Native smoke tests against a real MongoDB deployment are
environment-gated and skip when the relevant URI is unset.
The smoke harness does not require Docker and does not create external
deployments; provide URIs for deployments you manage.

Plain local MongoDB smoke:

```sh
MONGONGO_TEST_URI=mongodb://127.0.0.1:27017 mise exec -- ./gradlew :mongongo-core:macosArm64Test --rerun-tasks
```

Optional smoke URIs used by tests:

| Environment variable | Expected deployment |
| --- | --- |
| `MONGONGO_TEST_URI` | Plain MongoDB URI for ping, CRUD, typed CRUD, database, index, and session smoke coverage |
| `MONGONGO_AUTH_TEST_URI` | MongoDB URI with SCRAM-SHA-256 credentials |
| `MONGONGO_TLS_TEST_URI` | MongoDB URI that requires TLS |
| `MONGONGO_AUTH_TLS_TEST_URI` | MongoDB URI that requires SCRAM-SHA-256 credentials and TLS |
| `MONGONGO_SRV_TEST_URI` | `mongodb+srv://` URI |
| `MONGONGO_AUTH_SRV_TEST_URI` | `mongodb+srv://` URI with SCRAM-SHA-256 credentials |
| `MONGONGO_TRANSACTION_TEST_URI` | Replica set or sharded MongoDB URI that supports transactions |

When an environment variable is unset, the matching smoke test returns without
opening a network connection. `MONGONGO_TRANSACTION_TEST_URI` is separate from
`MONGONGO_TEST_URI` so standalone local MongoDB can cover plain CRUD smoke while
a replica-set URI is required for transaction commit and abort coverage.

Example:

```sh
MONGONGO_AUTH_TEST_URI='mongodb://user:p%40ssword@127.0.0.1:27017/app?authSource=admin' \
    mise exec -- ./gradlew :mongongo-core:macosArm64Test --rerun-tasks
```

Transaction smoke example:

```sh
MONGONGO_TRANSACTION_TEST_URI='mongodb://127.0.0.1:27017/app?replicaSet=rs0' \
    mise exec -- ./gradlew :mongongo-core:macosArm64Test --rerun-tasks
```

## Release workflow

Releases are driven by tags such as `v0.1.0`. The workflow validates Spotless,
JVM tests, macOS Arm64 tests, and local Maven publishing before attempting any
remote publication.

Remote Maven publication is intentionally gated by secrets. Configure all of
these before pushing a release tag:

- `MAVEN_PUBLISHING_REPOSITORY_URL`
- `MAVEN_PUBLISHING_USERNAME`
- `MAVEN_PUBLISHING_PASSWORD`
- `SIGNING_IN_MEMORY_KEY`
- `SIGNING_IN_MEMORY_KEY_PASSWORD`

If any required secret is missing, the publish job fails before the GitHub
Release is created. This prevents a tag workflow from silently pretending that
Maven artifacts were published. After Gradle uploads the artifacts through the
OSSRH Staging API compatibility endpoint, the workflow asks Central Portal to
upload and automatically publish the `io.github.misut` deployment.

## Design notes

- The default public API works with `BsonDocument` and `BsonValue` types.
  The filter and update DSLs are additive helpers that produce ordinary BSON
  documents; raw BSON remains the escape hatch for unsupported operators.
- Typed `MongoCollection<T>` values can use either an explicit `MongoCodec<T>` or
  the kotlinx.serialization BSON codec v0 for supported `@Serializable` data
  classes. Serialization-backed typed filters and updates use serializer
  field-name mapping, including `@SerialName`.
- Nested Kotlin property paths are not inferred. Use explicit `field("a.b")`
  BSON paths for nested filter and update paths.
- `commonMain` does not depend on the JVM MongoDB driver. The official JVM
  driver is used only in JVM tests for verification.
- Commands are implemented over MongoDB OP_MSG.
- `MongoCursor` supports suspending `next()`, `toList()`, and `close()`. If a
  cursor is not exhausted and you stop reading early, call `close()` to send
  `killCursors`.
- The client uses one connection per `MongoClient` and serializes requests on
  that connection. Connection pooling is deferred for v0.1 because the current
  transport owns one in-flight request/response pair at a time; adding a pool
  needs a larger checkout/checkin and authentication lifecycle.
- Clean ended server sessions are pooled per `MongoClient`. Dirty or expired
  sessions are ended instead of reused, and pooled sessions are ended when the
  client closes.
- `withTransaction` has bounded v0 retry support: it retries the whole
  transaction for server errors labeled `TransientTransactionError`, retries
  `commitTransaction` for `UnknownTransactionCommitResult`, and does not retry
  unlabeled errors. The callback may run more than once, so avoid non-idempotent
  side effects outside MongoDB writes in the callback. Manual transaction APIs
  remain single-attempt.
- Sessions send `lsid` with session-bound commands. Transactions send `lsid`,
  `txnNumber`, and `autocommit: false`; the first transaction operation also
  sends `startTransaction: true`.
- Causal consistency bookkeeping is not implemented yet: the client does not
  track `operationTime`, gossip `$clusterTime`, or add `readConcern.afterClusterTime`.

## License

mongongo is available under the [MIT License](LICENSE).
