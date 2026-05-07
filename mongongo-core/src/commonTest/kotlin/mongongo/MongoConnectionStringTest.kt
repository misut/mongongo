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
    }

    @Test
    fun rejectsUnsupportedConnectionStringShapes() {
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse("mongodb+srv://cluster.example.com")
        }
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse("mongodb://user:pass@localhost")
        }
        assertFailsWith<UnsupportedOperationException> {
            MongoConnectionStringParser.parse("mongodb://localhost/?tls=true")
        }
    }
}
