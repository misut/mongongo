package mongongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv

class NativeMongoSmokeTest {
    @Test
    fun pingsConfiguredMongoUri() = runTest {
        val uri = environment("MONGONGO_TEST_URI") ?: return@runTest
        val client = MongoClient.connect(uri)
        try {
            assertEquals(1.0, client.ping().ok)
        } finally {
            client.close()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environment(name: String): String? = getenv(name)?.toKString()
