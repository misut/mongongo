package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
