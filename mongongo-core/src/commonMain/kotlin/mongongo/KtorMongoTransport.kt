package mongongo

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers

internal class KtorMongoTransport private constructor(
    private val selectorManager: SelectorManager,
    private val socket: Socket,
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel
) {
    suspend fun send(requestId: Int, body: BsonDocument): BsonDocument {
        output.writeFully(OpMsg.encode(requestId = requestId, responseTo = 0, body = body))
        output.flush()

        val response = OpMsg.decode(input.readMongoMessage())
        require(response.responseTo == requestId) {
            "MongoDB responseTo ${response.responseTo} does not match request id $requestId"
        }
        return response.body
    }

    fun close() {
        socket.close()
        selectorManager.close()
    }

    companion object {
        suspend fun connect(host: MongoHost): KtorMongoTransport {
            val selectorManager = SelectorManager(Dispatchers.Default)
            try {
                val socket = aSocket(selectorManager).tcp().connect(host.hostname, host.port)
                return KtorMongoTransport(
                    selectorManager = selectorManager,
                    socket = socket,
                    input = socket.openReadChannel(),
                    output = socket.openWriteChannel()
                )
            } catch (throwable: Throwable) {
                selectorManager.close()
                throw throwable
            }
        }
    }
}

internal suspend fun ByteReadChannel.readMongoMessage(): ByteArray {
    val lengthBytes = ByteArray(4)
    readFully(lengthBytes)

    val messageLength = readInt32(lengthBytes, 0)
    require(messageLength >= 16) { "MongoDB message length is smaller than its header" }

    val remainingBytes = ByteArray(messageLength - 4)
    readFully(remainingBytes)

    return lengthBytes + remainingBytes
}
