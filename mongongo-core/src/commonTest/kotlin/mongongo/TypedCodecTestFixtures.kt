package mongongo

internal data class TestBook(val title: String)

internal object TestBookCodec : MongoCodec<TestBook> {
    override fun encode(value: TestBook): BsonDocument =
        BsonDocument("title" to BsonString(value.title))

    override fun decode(document: BsonDocument): TestBook =
        TestBook(
            title = document.getString("title")
                ?: error("Test book document did not contain string title")
        )
}

internal object FailingTestBookCodec : MongoCodec<TestBook> {
    override fun encode(value: TestBook): BsonDocument = TestBookCodec.encode(value)

    override fun decode(document: BsonDocument): TestBook {
        error("decode failed")
    }
}
