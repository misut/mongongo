package mongongo

import com.mongodb.ConnectionString
import com.mongodb.client.MongoClients
import de.flapdoodle.embed.mongo.commands.MongodArguments
import de.flapdoodle.embed.mongo.commands.ServerAddress
import de.flapdoodle.embed.mongo.config.Storage
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.transitions.Mongod
import de.flapdoodle.embed.process.io.ProcessOutput
import de.flapdoodle.reverse.Transition
import de.flapdoodle.reverse.transitions.Start
import org.bson.Document

const val DEFAULT_REPLICA_SET_NAME = "fusion-main"
const val DEFAULT_SERVICE_NAME_PREFIX = "fusion-main0"
const val DEFAULT_SHARD_COUNT = 2
val DEFAULT_VERSION = Version.V6_0_18
private const val PRIMARY_WAIT_TIMEOUT_MILLIS = 30_000L
private const val PRIMARY_WAIT_POLL_MILLIS = 250L

class ShardedEmbedMongoCluster (
    replicaSetName: String = DEFAULT_REPLICA_SET_NAME,
    serviceNamePrefix: String = DEFAULT_SERVICE_NAME_PREFIX,
    shardCount: Int = DEFAULT_SHARD_COUNT,
    version: Version = DEFAULT_VERSION
): EmbedMongoCluster {
    private val storage = Storage.of(replicaSetName, 0)
    private val mongods by lazy {
        (1..shardCount)
            .map {
                MongodWithStorage(id = "[$serviceNamePrefix$it]", storage = storage).start(version)
            }
            .apply {
                initiateReplicaSet(
                    replicaSetName,
                    *map { it.current().serverAddress }.toTypedArray()
                )
            }
    }

    private fun initiateReplicaSet(replicasetName: String, vararg serverAddresses: ServerAddress) {
        if (serverAddresses.isEmpty()) {
            throw IllegalArgumentException("At least one server address must be provided")
        }

        val members =
            serverAddresses.mapIndexed { idx, serverAddress ->
                Document(mapOf("_id" to idx, "host" to serverAddress.toString()))
            }

        MongoClients.create("mongodb://${serverAddresses.first()}").use { client ->
            client
                .getDatabase("admin")
                .runCommand(
                    Document(
                        mapOf(
                            "replSetInitiate" to
                                    Document(mapOf("_id" to replicasetName, "members" to members))
                        )
                    )
                )
        }
        waitForWritablePrimary(*serverAddresses)
    }

    override val connectionString: ConnectionString
        get() = ConnectionString("mongodb://${mongods.map { it.current().serverAddress }.joinToString(",")}")

    private fun waitForWritablePrimary(vararg serverAddresses: ServerAddress) {
        val deadline = System.currentTimeMillis() + PRIMARY_WAIT_TIMEOUT_MILLIS
        var lastFailure: Throwable? = null

        while (System.currentTimeMillis() < deadline) {
            for (serverAddress in serverAddresses) {
                try {
                    MongoClients.create("mongodb://$serverAddress").use { client ->
                        val hello = client.getDatabase("admin").runCommand(Document("hello", 1))
                        if (hello.getBoolean("isWritablePrimary", false) || hello.getBoolean("ismaster", false)) {
                            return
                        }
                    }
                } catch (throwable: Throwable) {
                    lastFailure = throwable
                }
            }

            Thread.sleep(PRIMARY_WAIT_POLL_MILLIS)
        }

        val failure = IllegalStateException("Embedded MongoDB replica set did not elect a writable primary")
        lastFailure?.let(failure::addSuppressed)
        throw failure
    }
}

private class MongodWithStorage(
    private val id: String,
    private val storage: Storage
) : Mongod() {
    override fun mongodArguments(): Transition<MongodArguments> =
        Start.to(MongodArguments::class.java)
            .initializedWith(
                MongodArguments.defaults()
                    .withIsShardServer(true)
                    .withUseNoJournal(false)
                    .withReplication(storage)
            )

    override fun processOutput(): Transition<ProcessOutput> =
        Start.to(ProcessOutput::class.java).initializedWith(ProcessOutput.namedConsole(id))
}
