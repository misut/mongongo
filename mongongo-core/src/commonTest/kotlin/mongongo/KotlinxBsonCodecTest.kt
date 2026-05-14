package mongongo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class CodecSimpleBook(
    val title: String,
    val pages: Int,
    val words: Long,
    val rating: Double,
    val available: Boolean
)

@Serializable
private data class CodecSerialNamedBook(
    @SerialName("book_title")
    val title: String
)

@Serializable
private data class CodecNullableBook(
    val subtitle: String?
)

@Serializable
private data class CodecAuthor(
    val name: String
)

@Serializable
private data class CodecNestedBook(
    val author: CodecAuthor,
    val tags: List<String>
)

@Serializable
private data class CodecNestedAuthorListBook(
    val authors: List<CodecAuthor>
)

@Serializable
private data class CodecIdentifiedBook(
    @SerialName("_id")
    val id: BsonObjectId
)

@Serializable
private data class CodecDefaultedBook(
    val title: String,
    val status: String = "draft"
)

@Serializable
private data class CodecMissingNullableBook(
    val title: String,
    val subtitle: String?
)

@Serializable
private data class CodecRequiredBook(
    val title: String,
    val pages: Int
)

@Serializable
private data class CodecUnsupportedBook(
    val metadata: Map<String, String>
)

@Serializable
private enum class CodecGenre {
    SF
}

@Serializable
private data class CodecEnumBook(
    val genre: CodecGenre
)

@Serializable
private data class CodecByteArrayBook(
    val payload: ByteArray
)

@Serializable
private data class CodecDateReadBook(
    val publishedAt: String
)

class KotlinxBsonCodecTest {
    @Test
    fun encodesAndDecodesSimpleDataClass() {
        val codec = KotlinxBsonCodec(CodecSimpleBook.serializer())
        val book = CodecSimpleBook(title = "Dawn", pages = 248, words = 91_000L, rating = 4.5, available = true)

        val document = codec.encode(book)

        assertEquals(BsonString("Dawn"), document["title"])
        assertEquals(BsonInt32(248), document["pages"])
        assertEquals(BsonInt64(91_000L), document["words"])
        assertEquals(BsonDouble(4.5), document["rating"])
        assertEquals(BsonBoolean(true), document["available"])
        assertEquals(book, codec.decode(document))
    }

    @Test
    fun usesSerialNameAsBsonFieldName() {
        val codec = KotlinxBsonCodec(CodecSerialNamedBook.serializer())
        val document = codec.encode(CodecSerialNamedBook("Kindred"))

        assertEquals(BsonString("Kindred"), document["book_title"])
        assertEquals(null, document["title"])
        assertEquals(CodecSerialNamedBook("Kindred"), codec.decode(BsonDocument("book_title" to BsonString("Kindred"))))
    }

    @Test
    fun encodesAndDecodesNullablePropertyAsBsonNull() {
        val codec = KotlinxBsonCodec(CodecNullableBook.serializer())
        val document = codec.encode(CodecNullableBook(subtitle = null))

        assertEquals(BsonNull, document["subtitle"])
        assertEquals(CodecNullableBook(subtitle = null), codec.decode(document))
    }

    @Test
    fun encodesAndDecodesNestedObjectAndListProperty() {
        val codec = KotlinxBsonCodec(CodecNestedBook.serializer())
        val document = codec.encode(CodecNestedBook(author = CodecAuthor("Octavia Butler"), tags = listOf("sf", "classic")))

        assertEquals(BsonDocument("name" to BsonString("Octavia Butler")), document["author"])
        assertEquals(BsonArray(listOf(BsonString("sf"), BsonString("classic"))), document["tags"])
        assertEquals(
            CodecNestedBook(author = CodecAuthor("Octavia Butler"), tags = listOf("sf", "classic")),
            codec.decode(document)
        )
    }

    @Test
    fun encodesAndDecodesListOfNestedObjects() {
        val codec = KotlinxBsonCodec(CodecNestedAuthorListBook.serializer())
        val document =
            codec.encode(
                CodecNestedAuthorListBook(
                    authors = listOf(CodecAuthor("Octavia Butler"), CodecAuthor("Ursula Le Guin"))
                )
            )

        assertEquals(
            BsonArray(
                listOf(
                    BsonDocument("name" to BsonString("Octavia Butler")),
                    BsonDocument("name" to BsonString("Ursula Le Guin"))
                )
            ),
            document["authors"]
        )
        assertEquals(
            CodecNestedAuthorListBook(
                authors = listOf(CodecAuthor("Octavia Butler"), CodecAuthor("Ursula Le Guin"))
            ),
            codec.decode(document)
        )
    }

    @Test
    fun encodesAndDecodesBsonObjectIdProperty() {
        val codec = KotlinxBsonCodec(CodecIdentifiedBook.serializer())
        val id = BsonObjectId.fromHex("00112233445566778899aabb")
        val document = codec.encode(CodecIdentifiedBook(id))

        assertEquals(id, document["_id"])
        assertEquals(CodecIdentifiedBook(id), codec.decode(document))
    }

    @Test
    fun missingDefaultedFieldUsesSerializerDefaultAndUnknownFieldsAreIgnored() {
        val codec = KotlinxBsonCodec(CodecDefaultedBook.serializer())
        val document =
            BsonDocument(
                "_id" to BsonObjectId.fromHex("112233445566778899aabbcc"),
                "title" to BsonString("Parable of the Sower")
            )

        assertEquals(CodecDefaultedBook(title = "Parable of the Sower"), codec.decode(document))
    }

    @Test
    fun missingNullableFieldDecodesAsNull() {
        val codec = KotlinxBsonCodec(CodecMissingNullableBook.serializer())

        assertEquals(
            CodecMissingNullableBook(title = "Dawn", subtitle = null),
            codec.decode(BsonDocument("title" to BsonString("Dawn")))
        )
    }

    @Test
    fun missingRequiredNonNullFieldFailsClearly() {
        val codec = KotlinxBsonCodec(CodecRequiredBook.serializer())
        val failure =
            assertFailsWith<SerializationException> {
                codec.decode(BsonDocument("title" to BsonString("Dawn")))
            }

        assertTrue(failure.message.orEmpty().contains("pages"))
    }

    @Test
    fun unsupportedMapFailsWithClearException() {
        val codec = KotlinxBsonCodec(CodecUnsupportedBook.serializer())
        val failure =
            assertFailsWith<SerializationException> {
                codec.encode(CodecUnsupportedBook(mapOf("genre" to "sf")))
            }

        assertTrue(failure.message.orEmpty().contains("Map"))
        assertTrue(failure.message.orEmpty().contains("kotlinx BSON codec v0"))
    }

    @Test
    fun unsupportedEnumFailsWithClearException() {
        val codec = KotlinxBsonCodec(CodecEnumBook.serializer())
        val failure =
            assertFailsWith<SerializationException> {
                codec.encode(CodecEnumBook(CodecGenre.SF))
            }

        assertTrue(failure.message.orEmpty().contains("enum"))
        assertTrue(failure.message.orEmpty().contains("kotlinx BSON codec v0"))
    }

    @Test
    fun unsupportedByteArrayFailsWithClearException() {
        val codec = KotlinxBsonCodec(CodecByteArrayBook.serializer())
        val failure =
            assertFailsWith<SerializationException> {
                codec.encode(CodecByteArrayBook(byteArrayOf(1, 2, 3)))
            }

        assertTrue(failure.message.orEmpty().contains("Byte"))
        assertTrue(failure.message.orEmpty().contains("kotlinx BSON codec v0"))
    }

    @Test
    fun unsupportedDateTimeBsonValueFailsWithClearException() {
        val codec = KotlinxBsonCodec(CodecDateReadBook.serializer())
        val failure =
            assertFailsWith<SerializationException> {
                codec.decode(BsonDocument("publishedAt" to BsonDateTime(1_700_000_000_000)))
            }

        assertTrue(failure.message.orEmpty().contains("BSON datetime"))
    }
}
