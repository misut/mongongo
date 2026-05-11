package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

        assertEquals("mongodb://localhost/app", tls.redactedUri)
        assertEquals("mongodb://localhost/app", ssl.redactedUri)
    }

    @Test
    fun rejectsUnsupportedConnectionStringShapes() {
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse("mongodb+srv://cluster.example.com")
        }
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse("mongodb://localhost/?tls=true")
        }
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse("mongodb://localhost/?ssl=true")
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
