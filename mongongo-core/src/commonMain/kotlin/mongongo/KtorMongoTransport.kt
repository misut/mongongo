package mongongo

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.tls
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers

internal interface MongoTransport {
    suspend fun send(requestId: Int, body: BsonDocument): BsonDocument

    fun close()
}

internal class MongoTransportException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

internal class KtorMongoTransport private constructor(
    private val selectorManager: SelectorManager,
    private val socket: Socket,
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel,
    private val host: MongoHost,
    private val security: MongoTransportSecurity
) : MongoTransport {
    override suspend fun send(requestId: Int, body: BsonDocument): BsonDocument {
        output.writeFully(OpMsg.encode(requestId = requestId, responseTo = 0, body = body))
        output.flush()

        val response = OpMsg.decode(input.readMongoMessage())
        require(response.responseTo == requestId) {
            "MongoDB responseTo ${response.responseTo} does not match request id $requestId"
        }
        return response.body
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            socket.close()
        } catch (throwable: Throwable) {
            failure = throwable
        }
        try {
            selectorManager.close()
        } catch (throwable: Throwable) {
            if (failure == null) {
                failure = throwable
            } else {
                failure.addSuppressed(throwable)
            }
        }
        if (failure != null) {
            throw MongoTransportException(
                "Failed to close MongoDB ${security.label} connection to ${host.endpoint()}",
                failure
            )
        }
    }

    companion object {
        suspend fun connect(host: MongoHost, tlsEnabled: Boolean): KtorMongoTransport {
            val selectorManager = SelectorManager(Dispatchers.Default)
            val security = if (tlsEnabled) MongoTransportSecurity.Tls else MongoTransportSecurity.Plain
            var socket: Socket? = null
            try {
                val connectedSocket =
                    try {
                        aSocket(selectorManager).tcp().connect(host.hostname, host.port)
                    } catch (throwable: Throwable) {
                        throw MongoTransportException(
                            "Failed to open MongoDB plain TCP connection to ${host.endpoint()}",
                            throwable
                        )
                    }
                socket = connectedSocket

                val activeSocket =
                    if (tlsEnabled) {
                        try {
                            connectedSocket.tls(coroutineContext = Dispatchers.Default) {
                                serverName = host.hostname
                            }
                        } catch (throwable: Throwable) {
                            try {
                                connectedSocket.close()
                            } catch (closeFailure: Throwable) {
                                throwable.addSuppressed(closeFailure)
                            }
                            throw MongoTransportException(
                                "Failed to establish MongoDB TLS connection to ${host.endpoint()}",
                                throwable
                            )
                        }
                    } else {
                        connectedSocket
                    }
                socket = activeSocket
                return KtorMongoTransport(
                    selectorManager = selectorManager,
                    socket = activeSocket,
                    input = activeSocket.openReadChannel(),
                    output = activeSocket.openWriteChannel(),
                    host = host,
                    security = security
                )
            } catch (throwable: Throwable) {
                try {
                    socket?.close()
                } catch (closeFailure: Throwable) {
                    throwable.addSuppressed(closeFailure)
                }
                try {
                    selectorManager.close()
                } catch (closeFailure: Throwable) {
                    throwable.addSuppressed(closeFailure)
                }
                throw throwable
            }
        }
    }
}

private enum class MongoTransportSecurity(val label: String) {
    Plain("plain TCP"),
    Tls("TLS")
}

private fun MongoHost.endpoint(): String = "$hostname:$port"

internal suspend fun ByteReadChannel.readMongoMessage(): ByteArray {
    val lengthBytes = ByteArray(4)
    readFully(lengthBytes)

    val messageLength = readInt32(lengthBytes, 0)
    require(messageLength >= 16) { "MongoDB message length is smaller than its header" }

    val remainingBytes = ByteArray(messageLength - 4)
    readFully(remainingBytes)

    return lengthBytes + remainingBytes
}
