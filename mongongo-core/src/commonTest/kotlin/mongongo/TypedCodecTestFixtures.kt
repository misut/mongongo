package mongongo

import kotlinx.serialization.Serializable

@Serializable
internal data class TestBook(val title: String)

internal object TestBookCodec : MongoCodec<TestBook> {
    private val codec = KotlinxBsonCodec(TestBook.serializer())

    override fun encode(value: TestBook): BsonDocument =
        codec.encode(value)

    override fun decode(document: BsonDocument): TestBook =
        codec.decode(document)
}

internal object FailingTestBookCodec : MongoCodec<TestBook> {
    override fun encode(value: TestBook): BsonDocument = TestBookCodec.encode(value)

    override fun decode(document: BsonDocument): TestBook {
        error("decode failed")
    }
}
