package mongongo

import com.mongodb.ConnectionString

interface EmbedMongoCluster {
    val connectionString: ConnectionString
}
