package mongongo

public interface MongoCodec<T : Any> {
    public fun encode(value: T): BsonDocument

    public fun decode(document: BsonDocument): T
}

public object BsonDocumentCodec : MongoCodec<BsonDocument> {
    override fun encode(value: BsonDocument): BsonDocument = value

    override fun decode(document: BsonDocument): BsonDocument = document
}

public typealias BsonCollection = MongoCollection<BsonDocument>

public typealias BsonCursor = MongoCursor<BsonDocument>
