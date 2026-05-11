package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            MongoConnectionStringParser.parse("mongodb+srv://cluster.example.com")
        }
        for (mechanism in listOf("SCRAM-SHA-1", "MONGODB-X509", "PLAIN", "GSSAPI", "MONGODB-AWS", "UNKNOWN")) {
            assertFailsWith<UnsupportedOperationException> {
                MongoConnectionStringParser.parse("mongodb://user:pass@localhost/?authMechanism=$mechanism")
            }
        }
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse("mongodb://localhost/?replicaSet=rs0")
        }
        assertFailsWith<IllegalArgumentException> {
            MongoConnectionStringParser.parse("mongodb://user:pass@localhost/?authSource=")
        }
    }
}
