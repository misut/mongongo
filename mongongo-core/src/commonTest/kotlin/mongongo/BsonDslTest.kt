package mongongo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class DslMappedBook(
    @SerialName("book_title")
    val title: String,
    val year: Int,
    val edition: Int,
    val archived: Boolean = false
)

@Serializable
private data class DslAmbiguousBook(
    @SerialName("primary")
    val title: String,
    val status: String
)

@Serializable
private data class DslUpdateBook(
    @SerialName("book_title")
    val title: String,
    @SerialName("draft_notes")
    val draftNotes: String? = null,
    @SerialName("published_year")
    val year: Int = 0,
    @SerialName("tags_field")
    val tags: List<String> = emptyList(),
    @SerialName("authors_field")
    val authors: List<String> = emptyList()
)

class BsonDslTest {
    @Test
    fun buildsFilterWithComparisonListAndLogicalOperators() {
        val actual =
            filter {
                "title" eq "Dune"
                "year" gte 1965
                "year" lt 1970
                "tags" inList listOf("sf", "classic")
                "format" nin listOf("abridged", "draft")
                or(filter { "status" eq "published" }, filter { "status" eq "backlist" })
                not { "archived" eq true }
            }

        val expected =
            BsonDocument(
                "title" to BsonString("Dune"),
                "year" to BsonDocument("\$gte" to BsonInt32(1965), "\$lt" to BsonInt32(1970)),
                "tags" to BsonDocument("\$in" to BsonArray(listOf(BsonString("sf"), BsonString("classic")))),
                "format" to BsonDocument("\$nin" to BsonArray(listOf(BsonString("abridged"), BsonString("draft")))),
                "\$or" to
                    BsonArray(
                        listOf(
                            BsonDocument("status" to BsonString("published")),
                            BsonDocument("status" to BsonString("backlist"))
                        )
                    ),
                "\$nor" to BsonArray(listOf(BsonDocument("archived" to BsonBoolean(true))))
            )

        assertEquals(expected, actual)
    }

    @Test
    fun buildsTypedPropertyFiltersUsingPropertyNames() {
        val actual =
            filter {
                TestBook::title eq "Kindred"
                TestBook::title ne "Dawn"
            }

        assertEquals(
            BsonDocument("title" to BsonDocument("\$eq" to BsonString("Kindred"), "\$ne" to BsonString("Dawn"))),
            actual
        )
    }

    @Test
    fun buildsSerializerAwareTypedPropertyFilters() {
        val actual =
            filter(DslMappedBook.serializer()) {
                DslMappedBook::title eq "Dune"
                DslMappedBook::title ne "Dawn"
                DslMappedBook::year gt 1950
                DslMappedBook::year gte 1965
                DslMappedBook::year lt 1970
                DslMappedBook::year lte 1966
                DslMappedBook::edition inList listOf(1, 2)
                and(typedFilter<DslMappedBook> { DslMappedBook::edition eq 1 })
                or(
                    typedFilter<DslMappedBook> { DslMappedBook::year eq 1965 },
                    typedFilter<DslMappedBook> { DslMappedBook::year eq 1966 }
                )
                not { DslMappedBook::archived eq true }
            }

        assertEquals(
            BsonDocument(
                "book_title" to BsonDocument("\$eq" to BsonString("Dune"), "\$ne" to BsonString("Dawn")),
                "year" to
                    BsonDocument(
                        "\$gt" to BsonInt32(1950),
                        "\$gte" to BsonInt32(1965),
                        "\$lt" to BsonInt32(1970),
                        "\$lte" to BsonInt32(1966)
                    ),
                "edition" to BsonDocument("\$in" to BsonArray(listOf(BsonInt32(1), BsonInt32(2)))),
                "\$and" to BsonArray(listOf(BsonDocument("edition" to BsonInt32(1)))),
                "\$or" to
                    BsonArray(
                        listOf(
                            BsonDocument("year" to BsonInt32(1965)),
                            BsonDocument("year" to BsonInt32(1966))
                        )
                    ),
                "\$nor" to BsonArray(listOf(BsonDocument("archived" to BsonBoolean(true))))
            ),
            actual
        )
    }

    @Test
    fun buildsExplicitFieldPathFiltersInRawAndTypedContexts() {
        val raw =
            filter {
                field("metadata.edition") eq 2
                field("metadata.rating") gte 4.0
                field("metadata.tags") inList listOf("sf", "classic")
            }

        assertEquals(
            BsonDocument(
                "metadata.edition" to BsonInt32(2),
                "metadata.rating" to BsonDocument("\$gte" to BsonDouble(4.0)),
                "metadata.tags" to BsonDocument("\$in" to BsonArray(listOf(BsonString("sf"), BsonString("classic"))))
            ),
            raw
        )

        val typed =
            typedFilter<DslMappedBook> {
                field("metadata.edition") eq 2
                DslMappedBook::title eq "Dune"
            }

        assertEquals(
            BsonDocument(
                "metadata.edition" to BsonInt32(2),
                "book_title" to BsonString("Dune")
            ),
            typed
        )
    }

    @Test
    fun rejectsBlankExplicitFieldPaths() {
        assertFailsWith<IllegalArgumentException> { field("") }
        assertFailsWith<IllegalArgumentException> { field("metadata..edition") }
    }

    @Test
    fun rejectsAmbiguousSerializerFieldLookup() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                typedFilter<DslAmbiguousBook> { DslAmbiguousBook::title eq "Dune" }
            }

        assertTrue(failure.message.orEmpty().contains("maps ambiguously"))
    }

    @Test
    fun buildsUpdateOperatorDocument() {
        val actual =
            update {
                set("status", "published")
                unset("draftNotes")
                inc("edition", 1)
                inc("words", 500L)
                inc("rating", 0.5)
                push("tags", "sf")
                pull("tags", "draft")
                addToSet("authors", "Octavia Butler")
            }

        val expected =
            BsonDocument(
                "\$set" to BsonDocument("status" to BsonString("published")),
                "\$unset" to BsonDocument("draftNotes" to BsonString("")),
                "\$inc" to
                    BsonDocument(
                        "edition" to BsonInt32(1),
                        "words" to BsonInt64(500L),
                        "rating" to BsonDouble(0.5)
                    ),
                "\$push" to BsonDocument("tags" to BsonString("sf")),
                "\$pull" to BsonDocument("tags" to BsonString("draft")),
                "\$addToSet" to BsonDocument("authors" to BsonString("Octavia Butler"))
            )

        assertEquals(expected, actual)
    }

    @Test
    fun stringBasedUpdateDslUsesLiteralFieldNames() {
        val actual =
            update {
                set("title", "Dune")
            }

        assertEquals(BsonDocument("\$set" to BsonDocument("title" to BsonString("Dune"))), actual)
    }

    @Test
    fun buildsSerializerAwareTypedUpdateOperatorDocument() {
        val actual =
            typedUpdate<DslUpdateBook> {
                set(DslUpdateBook::title, "Dune")
                unset(DslUpdateBook::draftNotes)
                inc(DslUpdateBook::year, 1)
                push(DslUpdateBook::tags, "sf")
                pull(DslUpdateBook::tags, "draft")
                addToSet(DslUpdateBook::authors, "Frank Herbert")
            }

        val expected =
            BsonDocument(
                "\$set" to BsonDocument("book_title" to BsonString("Dune")),
                "\$unset" to BsonDocument("draft_notes" to BsonString("")),
                "\$inc" to BsonDocument("published_year" to BsonInt32(1)),
                "\$push" to BsonDocument("tags_field" to BsonString("sf")),
                "\$pull" to BsonDocument("tags_field" to BsonString("draft")),
                "\$addToSet" to BsonDocument("authors_field" to BsonString("Frank Herbert"))
            )

        assertEquals(expected, actual)
    }

    @Test
    fun buildsExplicitFieldPathUpdatesInRawAndTypedContexts() {
        val raw =
            update {
                set(field("metadata.edition"), 3)
                unset(field("metadata.previous"))
                inc(field("stats.reads"), 1)
                push(field("metadata.tags"), "sf")
                pull(field("metadata.tags"), "draft")
                addToSet(field("metadata.authors"), "Frank Herbert")
            }

        assertEquals(
            BsonDocument(
                "\$set" to BsonDocument("metadata.edition" to BsonInt32(3)),
                "\$unset" to BsonDocument("metadata.previous" to BsonString("")),
                "\$inc" to BsonDocument("stats.reads" to BsonInt32(1)),
                "\$push" to BsonDocument("metadata.tags" to BsonString("sf")),
                "\$pull" to BsonDocument("metadata.tags" to BsonString("draft")),
                "\$addToSet" to BsonDocument("metadata.authors" to BsonString("Frank Herbert"))
            ),
            raw
        )

        val typed =
            typedUpdate<DslUpdateBook> {
                set(field("metadata.edition"), 3)
                set(DslUpdateBook::title, "Dune")
            }

        assertEquals(
            BsonDocument(
                "\$set" to
                    BsonDocument(
                        "metadata.edition" to BsonInt32(3),
                        "book_title" to BsonString("Dune")
                    )
            ),
            typed
        )
    }

    @Test
    fun buildsSerializerAwareUpdateWithExplicitSerializer() {
        val actual =
            update(DslUpdateBook.serializer()) {
                set(DslUpdateBook::title, "Dune")
            }

        assertEquals(BsonDocument("\$set" to BsonDocument("book_title" to BsonString("Dune"))), actual)
    }

    @Test
    fun rejectsAmbiguousTypedUpdateFieldLookup() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                typedUpdate<DslAmbiguousBook> { set(DslAmbiguousBook::title, "Dune") }
            }

        assertTrue(failure.message.orEmpty().contains("maps ambiguously"))
    }

    @Test
    fun documentBuilderAcceptsPrimitiveDslValues() {
        val actual =
            bsonDocument {
                value("title", "The Dispossessed")
                value("published", 1974)
                value("tags", listOf("sf", "classic"))
                value("metadata", null)
            }

        assertEquals(
            BsonDocument(
                "title" to BsonString("The Dispossessed"),
                "published" to BsonInt32(1974),
                "tags" to BsonArray(listOf(BsonString("sf"), BsonString("classic"))),
                "metadata" to BsonNull
            ),
            actual
        )
    }

    @Test
    fun rejectsUnsupportedDslValues() {
        val failure = assertFailsWith<IllegalStateException> { filter { "pages" eq 12.toShort() } }

        assertTrue(failure.message.orEmpty().contains("Unsupported BSON DSL value"))
    }
}
