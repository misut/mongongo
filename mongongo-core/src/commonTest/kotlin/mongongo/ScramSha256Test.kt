package mongongo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScramSha256Test {
    @Test
    fun generatesAndVerifiesScramSha256Messages() = runTest {
        val conversation =
            ScramSha256Conversation.start(
                MongoCredential(
                    username = "user",
                    password = "pencil",
                    authSource = "admin"
                ),
                clientNonce = "client-nonce-123"
            )

        assertEquals("n,,n=user,r=client-nonce-123", conversation.clientFirstMessage)

        val clientFinal =
            conversation.receiveServerFirst(
                "r=client-nonce-123server-extra-456,s=c2FsdHktc2FsdA==,i=4096"
            )

        assertEquals(
            "c=biws,r=client-nonce-123server-extra-456,p=hCahUad3VH0aN9EmQ9r0pGXdbAqmE/nwrYazTW2OtPE=",
            clientFinal
        )
        conversation.verifyServerFinal("v=V2CKSnQAHjd7fG9gWAS4Z4nPDVI6v90XblTFGNP8oeU=")
    }

    @Test
    fun escapesScramUsernameWithoutSaslprep() {
        val conversation =
            ScramSha256Conversation.start(
                MongoCredential(
                    username = "a,b=c",
                    password = "pencil",
                    authSource = "admin"
                ),
                clientNonce = "client-nonce"
            )

        assertEquals("n,,n=a=2Cb=3Dc,r=client-nonce", conversation.clientFirstMessage)
    }

    @Test
    fun rejectsServerNonceMismatch() = runTest {
        val conversation =
            ScramSha256Conversation.start(
                MongoCredential(username = "user", password = "pencil", authSource = "admin"),
                clientNonce = "client-nonce"
            )

        assertFailsWith<MongoAuthenticationException> {
            conversation.receiveServerFirst("r=other-nonce,s=c2FsdA==,i=4096")
        }
    }

    @Test
    fun rejectsServerSignatureMismatch() = runTest {
        val conversation =
            ScramSha256Conversation.start(
                MongoCredential(username = "user", password = "pencil", authSource = "admin"),
                clientNonce = "client-nonce-123"
            )
        conversation.receiveServerFirst("r=client-nonce-123server-extra-456,s=c2FsdHktc2FsdA==,i=4096")

        assertFailsWith<MongoAuthenticationException> {
            conversation.verifyServerFinal("v=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        }
    }

    @Test
    fun rejectsLowIterationCount() = runTest {
        val conversation =
            ScramSha256Conversation.start(
                MongoCredential(username = "user", password = "pencil", authSource = "admin"),
                clientNonce = "client-nonce"
            )

        assertFailsWith<MongoAuthenticationException> {
            conversation.receiveServerFirst("r=client-nonceserver,s=c2FsdA==,i=4095")
        }
    }

    @Test
    fun rejectsNonAsciiPasswordForV0() = runTest {
        val conversation =
            ScramSha256Conversation.start(
                MongoCredential(username = "user", password = "pencíl", authSource = "admin"),
                clientNonce = "client-nonce"
            )

        assertFailsWith<MongoAuthenticationException> {
            conversation.receiveServerFirst("r=client-nonceserver,s=c2FsdA==,i=4096")
        }
    }
}
