package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MongoConnectionStringTest {
    @Test
    fun parsesSingleHostWithDefaultPort() {
        val parsed = MongoConnectionStringParser.parse("mongodb://localhost")

        assertEquals(listOf(MongoHost("localhost", 27017)), parsed.hosts)
        assertEquals(null, parsed.database)
    }

    @Test
    fun parsesMultipleHostsAndDatabase() {
        val parsed = MongoConnectionStringParser.parse("mongodb://one:27018,two/test")

        assertEquals(listOf(MongoHost("one", 27018), MongoHost("two", 27017)), parsed.hosts)
        assertEquals("test", parsed.database)
        assertEquals(null, parsed.credential)
        assertEquals("mongodb://one:27018,two/test", parsed.redactedUri)
    }

    @Test
    fun parsesAuthenticationCredentialsAndOptions() {
        val parsed =
            MongoConnectionStringParser.parse(
                "mongodb://us%40er:p%40ss%3Aword@localhost:27018/app?authSource=admin&authMechanism=SCRAM-SHA-256"
            )

        assertEquals(listOf(MongoHost("localhost", 27018)), parsed.hosts)
        assertEquals("app", parsed.database)
        assertEquals(
            MongoCredential(
                username = "us@er",
                password = "p@ss:word",
                authSource = "admin",
                mechanism = ScramSha256Mechanism
            ),
            parsed.credential
        )
        assertEquals(
            "mongodb://<credentials>@localhost:27018/app?authSource=admin&authMechanism=SCRAM-SHA-256",
            parsed.redactedUri
        )
    }

    @Test
    fun defaultsAuthenticationSourceFromPathDatabaseAndThenAdmin() {
        val withDatabase = MongoConnectionStringParser.parse("mongodb://user:password@localhost/application")
        val withoutDatabase = MongoConnectionStringParser.parse("mongodb://user:password@localhost")

        assertEquals("application", withDatabase.credential?.authSource)
        assertEquals("admin", withoutDatabase.credential?.authSource)
        assertEquals(ScramSha256Mechanism, withDatabase.credential?.mechanism)
    }

    @Test
    fun allowsDisabledTlsAndSslOptions() {
        val tls = MongoConnectionStringParser.parse("mongodb://localhost/app?tls=false")
        val ssl = MongoConnectionStringParser.parse("mongodb://localhost/app?ssl=false")

        assertFalse(tls.tlsEnabled)
        assertFalse(ssl.tlsEnabled)
        assertEquals("mongodb://localhost/app", tls.redactedUri)
        assertEquals("mongodb://localhost/app", ssl.redactedUri)
    }

    @Test
    fun enablesTlsAndSslOptions() {
        val tls = MongoConnectionStringParser.parse("mongodb://localhost/app?tls=true")
        val ssl = MongoConnectionStringParser.parse("mongodb://localhost/app?ssl=true")

        assertTrue(tls.tlsEnabled)
        assertTrue(ssl.tlsEnabled)
        assertEquals("mongodb://localhost/app?tls=true", tls.redactedUri)
        assertEquals("mongodb://localhost/app?tls=true", ssl.redactedUri)
    }

    @Test
    fun parsesSrvConnectionStringThroughMongoTcpLookup() {
        val resolver =
            FakeMongoDnsResolver(
                srvRecords =
                    mapOf(
                        "_mongodb._tcp.cluster.example.com" to
                            listOf(MongoSrvRecord("mongo1.example.com.", 27017))
                    )
            )

        val parsed = MongoConnectionStringParser.parse("mongodb+srv://cluster.example.com/test", resolver)

        assertEquals(listOf("cluster.example.com"), resolver.txtQueries)
        assertEquals(listOf("_mongodb._tcp.cluster.example.com"), resolver.srvQueries)
        assertEquals(listOf(MongoHost("mongo1.example.com", 27017)), parsed.hosts)
        assertEquals("test", parsed.database)
        assertTrue(parsed.tlsEnabled)
        assertEquals("mongodb://mongo1.example.com/test?tls=true", parsed.redactedUri)
    }

    @Test
    fun sendsSrvRecordsThroughExistingMultiHostFlow() = runTest {
        val first = MongoHost("mongo1.example.com", 27017)
        val second = MongoHost("mongo2.example.com", 27017)
        val firstTransport = ScriptedMongoTransport(hello(isWritablePrimary = false))
        val secondTransport = ScriptedMongoTransport(hello(isWritablePrimary = true), ok())
        val transportConnector =
            ScriptedTransportConnector(
                mapOf(
                    first to firstTransport,
                    second to secondTransport
                )
            )
        val resolver =
            FakeMongoDnsResolver(
                srvRecords =
                    mapOf(
                        "_mongodb._tcp.cluster.example.com" to
                            listOf(
                                MongoSrvRecord(first.hostname, first.port),
                                MongoSrvRecord(second.hostname, second.port)
                            )
                    )
            )

        val client =
            MongoClient.connect(
                uri = "mongodb+srv://cluster.example.com/test",
                nonceGenerator = MongoNonceGenerator { "unused" },
                dnsResolver = resolver,
                transportConnector = transportConnector
            )
        try {
            assertEquals(1.0, client.ping("test").ok)
        } finally {
            client.close()
        }

        assertEquals(listOf(first, second), transportConnector.connectedHosts)
        assertTrue(firstTransport.closed)
        assertTrue(secondTransport.closed)
        assertEquals(BsonString("test"), secondTransport.sent.last()["\$db"])
    }

    @Test
    fun mergesSrvTxtAndUriOptionsWithUriPrecedence() {
        val resolver =
            FakeMongoDnsResolver(
                srvRecords =
                    mapOf(
                        "_mongodb._tcp.cluster.example.com" to
                            listOf(MongoSrvRecord("mongo1.example.com", 27017))
                    ),
                txtRecords =
                    mapOf(
                        "cluster.example.com" to
                            listOf("authSource=txt-admin&replicaSet=atlas-rs&tls=true&loadBalanced=false")
                    )
            )

        val parsed =
            MongoConnectionStringParser.parse(
                "mongodb+srv://user:password@cluster.example.com/app?authSource=uri-admin&tls=false",
                resolver
            )

        assertFalse(parsed.tlsEnabled)
        assertEquals("atlas-rs", parsed.replicaSet)
        assertEquals(false, parsed.loadBalanced)
        assertEquals("uri-admin", parsed.credential?.authSource)
        assertEquals(
            "mongodb://<credentials>@mongo1.example.com/app?authSource=uri-admin&replicaSet=atlas-rs&loadBalanced=false",
            parsed.redactedUri
        )
    }

    @Test
    fun allowsTxtTlsAndSslOverridesForSrvConnectionStrings() {
        val tlsDisabled =
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(
                    srvRecords =
                        mapOf(
                            "_mongodb._tcp.cluster.example.com" to
                                listOf(MongoSrvRecord("mongo1.example.com", 27017))
                        ),
                    txtRecords = mapOf("cluster.example.com" to listOf("tls=false"))
                )
            )
        val sslDisabled =
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(
                    srvRecords =
                        mapOf(
                            "_mongodb._tcp.cluster.example.com" to
                                listOf(MongoSrvRecord("mongo1.example.com", 27017))
                        ),
                    txtRecords = mapOf("cluster.example.com" to listOf("ssl=false"))
                )
            )

        assertFalse(tlsDisabled.tlsEnabled)
        assertFalse(sslDisabled.tlsEnabled)
    }

    @Test
    fun allowsDuplicateEquivalentTlsAndSslOptions() {
        val enabled = MongoConnectionStringParser.parse("mongodb://localhost/app?tls=true&ssl=true")
        val disabled = MongoConnectionStringParser.parse("mongodb://localhost/app?tls=false&ssl=false")

        assertTrue(enabled.tlsEnabled)
        assertFalse(disabled.tlsEnabled)
        assertEquals("mongodb://localhost/app?tls=true", enabled.redactedUri)
        assertEquals("mongodb://localhost/app", disabled.redactedUri)
    }

    @Test
    fun rejectsConflictingTlsAndSslOptions() {
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse("mongodb://localhost/app?tls=true&ssl=false")
        }
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse("mongodb://localhost/app?tls=false&ssl=true")
        }
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse("mongodb://localhost/app?tls=true&tls=false")
        }
    }

    @Test
    fun parsesAuthenticationCredentialsWithTlsOption() {
        val parsed =
            MongoConnectionStringParser.parse(
                "mongodb://user:password@localhost/app?authSource=admin&tls=true"
            )

        assertTrue(parsed.tlsEnabled)
        assertEquals(
            MongoCredential(username = "user", password = "password", authSource = "admin"),
            parsed.credential
        )
        assertEquals("mongodb://<credentials>@localhost/app?authSource=admin&tls=true", parsed.redactedUri)
    }

    @Test
    fun rejectsUnsupportedTlsOptions() {
        for (
            option in
                listOf(
                    "tlsCAFile=/tmp/ca.pem",
                    "tlsCertificateKeyFile=/tmp/client.pem",
                    "tlsCertificateKeyFilePassword=secret",
                    "tlsAllowInvalidCertificates=true",
                    "tlsAllowInvalidHostnames=true",
                    "tlsInsecure=true",
                    "tlsDisableOCSPEndpointCheck=true",
                    "tlsDisableCertificateRevocationCheck=true",
                    "sslCAFile=/tmp/ca.pem"
                )
        ) {
            assertFailsWith<UnsupportedOperationException> {
                MongoConnectionStringParser.parse("mongodb://localhost/app?$option")
            }
        }
    }

    @Test
    fun rejectsUnsupportedConnectionStringShapes() {
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse("mongodb://localhost/?loadBalanced=true")
        }
        for (mechanism in listOf("SCRAM-SHA-1", "MONGODB-X509", "PLAIN", "GSSAPI", "MONGODB-AWS", "UNKNOWN")) {
            assertFailsWith<UnsupportedOperationException> {
                MongoConnectionStringParser.parse("mongodb://user:pass@localhost/?authMechanism=$mechanism")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse("mongodb://user:pass@localhost/?authSource=")
        }
    }

    @Test
    fun rejectsInvalidSrvConnectionStringsBeforeDnsLookup() {
        val resolver = FakeMongoDnsResolver()

        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse("mongodb+srv://cluster.example.com:27017/test", resolver)
        }
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse("mongodb+srv://one.example.com,two.example.com/test", resolver)
        }

        assertEquals(emptyList(), resolver.txtQueries)
        assertEquals(emptyList(), resolver.srvQueries)
    }

    @Test
    fun rejectsEmptySrvResultsAndDnsFailures() {
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(srvRecords = mapOf("_mongodb._tcp.cluster.example.com" to emptyList()))
            )
        }
        assertFailsWith<MongoDnsException> {
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(srvFailure = IllegalStateException("boom"))
            )
        }
        assertFailsWith<MongoDnsException> {
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(txtFailure = IllegalStateException("boom"))
            )
        }
    }

    @Test
    fun rejectsUnsupportedAndConflictingTxtOptions() {
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(
                    srvRecords =
                        mapOf(
                            "_mongodb._tcp.cluster.example.com" to
                                listOf(MongoSrvRecord("mongo1.example.com", 27017))
                        ),
                    txtRecords = mapOf("cluster.example.com" to listOf("retryWrites=true"))
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(
                    srvRecords =
                        mapOf(
                            "_mongodb._tcp.cluster.example.com" to
                                listOf(MongoSrvRecord("mongo1.example.com", 27017))
                        ),
                    txtRecords = mapOf("cluster.example.com" to listOf("tls=true&ssl=false"))
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(
                    srvRecords =
                        mapOf(
                            "_mongodb._tcp.cluster.example.com" to
                                listOf(MongoSrvRecord("mongo1.example.com", 27017))
                        ),
                    txtRecords = mapOf("cluster.example.com" to listOf("authSource=admin", "replicaSet=rs0"))
                )
            )
        }
    }

    @Test
    fun rejectsSrvTargetsOutsideParentDomain() {
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse(
                "mongodb+srv://cluster.example.com/test",
                FakeMongoDnsResolver(
                    srvRecords =
                        mapOf(
                            "_mongodb._tcp.cluster.example.com" to
                                listOf(MongoSrvRecord("mongo1.other.example.net", 27017))
                        )
                )
            )
        }
    }
}

private class FakeMongoDnsResolver(
    private val srvRecords: Map<String, List<MongoSrvRecord>> = emptyMap(),
    private val txtRecords: Map<String, List<String>> = emptyMap(),
    private val srvFailure: Throwable? = null,
    private val txtFailure: Throwable? = null
) : MongoDnsResolver {
    val srvQueries = mutableListOf<String>()
    val txtQueries = mutableListOf<String>()

    override fun lookupSrv(name: String): List<MongoSrvRecord> {
        srvQueries.add(name)
        srvFailure?.let { throw it }
        return srvRecords[name].orEmpty()
    }

    override fun lookupTxt(name: String): List<String> {
        txtQueries.add(name)
        txtFailure?.let { throw it }
        return txtRecords[name].orEmpty()
    }
}

private class ScriptedTransportConnector(
    private val transports: Map<MongoHost, ScriptedMongoTransport>
) : MongoTransportConnector {
    val connectedHosts = mutableListOf<MongoHost>()

    override suspend fun connect(host: MongoHost, tlsEnabled: Boolean): MongoTransport {
        connectedHosts.add(host)
        return transports[host] ?: error("No scripted transport for $host")
    }
}

private class ScriptedMongoTransport(vararg responses: BsonDocument) : MongoTransport {
    private val responses = ArrayDeque(responses.toList())
    val sent = mutableListOf<BsonDocument>()
    var closed = false

    override suspend fun send(requestId: Int, body: BsonDocument): BsonDocument {
        sent.add(body)
        return responses.removeFirst()
    }

    override fun close() {
        closed = true
    }
}

private fun hello(isWritablePrimary: Boolean): BsonDocument =
    BsonDocument(
        "ok" to BsonDouble(1.0),
        "isWritablePrimary" to BsonBoolean(isWritablePrimary),
        "maxWireVersion" to BsonInt32(21),
        "maxMessageSizeBytes" to BsonInt32(48_000_000),
        "maxBsonObjectSize" to BsonInt32(16_777_216)
    )

private fun ok(): BsonDocument = BsonDocument("ok" to BsonDouble(1.0))
