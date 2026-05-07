package mongongo

internal data class OpMsgFrame(
    val requestId: Int,
    val responseTo: Int,
    val body: BsonDocument
)

internal object OpMsg {
    private const val HeaderLength = 16
    private const val FlagBitsLength = 4
    private const val SectionKindLength = 1
    private const val OpMsgCode = 2013
    private const val ChecksumPresentFlag = 1
    private const val BodySectionKind = 0

    fun encode(requestId: Int, responseTo: Int, body: BsonDocument): ByteArray {
        val documentBytes = BsonCodec.encode(body)
        val messageLength = HeaderLength + FlagBitsLength + SectionKindLength + documentBytes.size
        val bytes = ByteArray(messageLength)

        writeInt32(bytes, 0, messageLength)
        writeInt32(bytes, 4, requestId)
        writeInt32(bytes, 8, responseTo)
        writeInt32(bytes, 12, OpMsgCode)
        writeInt32(bytes, 16, 0)
        bytes[20] = BodySectionKind.toByte()
        documentBytes.copyInto(bytes, destinationOffset = 21)

        return bytes
    }

    fun decode(bytes: ByteArray): OpMsgFrame {
        require(bytes.size >= HeaderLength + FlagBitsLength + SectionKindLength) {
            "OP_MSG frame is shorter than the minimum message length"
        }

        val messageLength = readInt32(bytes, 0)
        require(messageLength == bytes.size) { "OP_MSG frame length does not match its header" }

        val requestId = readInt32(bytes, 4)
        val responseTo = readInt32(bytes, 8)
        val opCode = readInt32(bytes, 12)
        require(opCode == OpMsgCode) { "Unsupported MongoDB opcode $opCode" }

        val flags = readInt32(bytes, 16)
        require(flags and ChecksumPresentFlag == 0) { "OP_MSG checksum frames are not supported yet" }
        require(bytes[20].toInt() == BodySectionKind) { "Only OP_MSG kind 0 body sections are supported" }

        return OpMsgFrame(
            requestId = requestId,
            responseTo = responseTo,
            body = BsonCodec.decodeDocument(bytes.copyOfRange(21, bytes.size))
        )
    }
}

internal fun readInt32(bytes: ByteArray, offset: Int): Int {
    require(offset >= 0 && offset + 4 <= bytes.size) { "Cannot read int32 outside byte array bounds" }
    var result = 0
    repeat(4) { index ->
        result = result or ((bytes[offset + index].toInt() and 0xff) shl (index * 8))
    }
    return result
}

private fun writeInt32(bytes: ByteArray, offset: Int, value: Int) {
    require(offset >= 0 && offset + 4 <= bytes.size) { "Cannot write int32 outside byte array bounds" }
    repeat(4) { index ->
        bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}
