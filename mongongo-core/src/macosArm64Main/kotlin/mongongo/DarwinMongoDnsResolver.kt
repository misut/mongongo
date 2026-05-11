package mongongo

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.darwin.DNSServiceErrorType
import platform.darwin.DNSServiceFlags
import platform.darwin.DNSServiceProcessResult
import platform.darwin.DNSServiceQueryRecord
import platform.darwin.DNSServiceRef
import platform.darwin.DNSServiceRefDeallocate
import platform.darwin.DNSServiceRefSockFD
import platform.darwin.DNSServiceRefVar
import platform.darwin.kDNSServiceClass_IN
import platform.darwin.kDNSServiceErr_NoError
import platform.darwin.kDNSServiceErr_NoSuchRecord
import platform.darwin.kDNSServiceErr_Timeout
import platform.darwin.kDNSServiceFlagsMoreComing
import platform.darwin.kDNSServiceType_SRV
import platform.darwin.kDNSServiceType_TXT
import platform.posix.fd_set
import platform.posix.posix_FD_SET
import platform.posix.posix_FD_ZERO
import platform.posix.select
import platform.posix.timeval
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
internal actual object SystemMongoDnsResolver : MongoDnsResolver {
    override fun lookupSrv(name: String): List<MongoSrvRecord> =
        query(name = name, type = kDNSServiceType_SRV.toUShort()).map { bytes ->
            require(bytes.size >= 7) { "Invalid DNS SRV record for $name" }
            MongoSrvRecord(
                hostname = readDomainName(bytes = bytes, offset = 6).trimEnd('.'),
                port = readUInt16(bytes = bytes, offset = 4)
            )
        }

    override fun lookupTxt(name: String): List<String> =
        query(name = name, type = kDNSServiceType_TXT.toUShort()).map(::readTxtRecord)

    private fun query(name: String, type: UShort): List<ByteArray> {
        val state = DarwinDnsQueryState()
        val stableRef = StableRef.create(state)
        return memScoped {
            val ref = alloc<DNSServiceRefVar>()
            val status =
                DNSServiceQueryRecord(
                    sdRef = ref.ptr,
                    flags = 0u,
                    interfaceIndex = 0u,
                    fullname = name,
                    rrtype = type,
                    rrclass = kDNSServiceClass_IN.toUShort(),
                    callBack = queryRecordCallback,
                    context = stableRef.asCPointer()
                )
            if (status != kDNSServiceErr_NoError) {
                stableRef.dispose()
                throw MongoDnsException("Failed to start DNS query for $name type $type: $status")
            }

            val serviceRef = ref.value
            try {
                processResults(name = name, type = type, serviceRef = serviceRef, state = state)
                if (state.errorCode != null) {
                    val errorCode = state.errorCode
                    if (errorCode == kDNSServiceErr_NoSuchRecord || errorCode == kDNSServiceErr_Timeout) {
                        emptyList()
                    } else {
                        throw MongoDnsException("DNS query for $name type $type failed with error $errorCode")
                    }
                } else {
                    state.records.toList()
                }
            } finally {
                DNSServiceRefDeallocate(serviceRef)
                stableRef.dispose()
            }
        }
    }

    private fun processResults(name: String, type: UShort, serviceRef: DNSServiceRef?, state: DarwinDnsQueryState) {
        val socket = DNSServiceRefSockFD(serviceRef)
        if (socket < 0) {
            throw MongoDnsException("DNS query for $name type $type did not provide a socket")
        }

        val started = TimeSource.Monotonic.markNow()
        while (!state.finished && started.elapsedNow() < 5.seconds) {
            memScoped {
                val readSet = alloc<fd_set>()
                val timeout = alloc<timeval>()
                posix_FD_ZERO(readSet.ptr)
                posix_FD_SET(socket, readSet.ptr)
                timeout.tv_sec = 1.convert()
                timeout.tv_usec = 0.convert()

                val selected = select(socket + 1, readSet.ptr, null, null, timeout.ptr)
                if (selected < 0) {
                    throw MongoDnsException("DNS query for $name type $type failed while waiting for results")
                }
                if (selected > 0) {
                    val processed = DNSServiceProcessResult(serviceRef)
                    if (processed != kDNSServiceErr_NoError) {
                        throw MongoDnsException("DNS query for $name type $type failed with error $processed")
                    }
                }
            }
        }
    }
}

private class DarwinDnsQueryState {
    val records = mutableListOf<ByteArray>()
    var errorCode: Int? = null
    var finished: Boolean = false
}

@OptIn(ExperimentalForeignApi::class)
private val queryRecordCallback =
    staticCFunction<
        DNSServiceRef?,
        DNSServiceFlags,
        UInt,
        DNSServiceErrorType,
        CPointer<ByteVar>?,
        UShort,
        UShort,
        UShort,
        COpaquePointer?,
        UInt,
        COpaquePointer?,
        Unit
    > { _, flags, _, errorCode, _, _, _, rdlen, rdata, _, context ->
        val state = context?.asStableRef<DarwinDnsQueryState>()?.get() ?: return@staticCFunction
        if (errorCode != kDNSServiceErr_NoError) {
            state.errorCode = errorCode
            state.finished = true
            return@staticCFunction
        }

        if (rdata != null && rdlen.toInt() > 0) {
            val data = rdata.reinterpret<ByteVar>()
            val bytes = ByteArray(rdlen.toInt()) { index -> data[index] }
            state.records.add(bytes)
        }
        if ((flags and kDNSServiceFlagsMoreComing) == 0u) {
            state.finished = true
        }
    }

private fun readUInt16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

private fun readDomainName(bytes: ByteArray, offset: Int): String {
    val labels = mutableListOf<String>()
    var index = offset
    while (index < bytes.size) {
        val length = bytes[index].toInt() and 0xff
        require((length and 0xc0) == 0) { "Compressed DNS names are not supported in SRV record data" }
        index++
        if (length == 0) {
            return labels.joinToString(".")
        }
        require(index + length <= bytes.size) { "Invalid DNS name in SRV record data" }
        labels.add(bytes.copyOfRange(index, index + length).decodeToString())
        index += length
    }
    throw IllegalArgumentException("DNS SRV record target did not terminate")
}

private fun readTxtRecord(bytes: ByteArray): String {
    val builder = StringBuilder()
    var index = 0
    while (index < bytes.size) {
        val length = bytes[index].toInt() and 0xff
        index++
        require(index + length <= bytes.size) { "Invalid DNS TXT record data" }
        builder.append(bytes.copyOfRange(index, index + length).decodeToString())
        index += length
    }
    return builder.toString()
}
