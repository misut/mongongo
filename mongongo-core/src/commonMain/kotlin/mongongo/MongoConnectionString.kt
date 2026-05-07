package mongongo

internal const val DefaultMongoPort = 27017

internal data class MongoHost(
    val hostname: String,
    val port: Int = DefaultMongoPort
)

internal data class MongoConnectionString(
    val hosts: List<MongoHost>,
    val database: String?
) {
    val primaryHost: MongoHost
        get() = hosts.first()
}

internal object MongoConnectionStringParser {
    fun parse(uri: String): MongoConnectionString {
        if (uri.startsWith("mongodb+srv://")) {
            throw UnsupportedOperationException("mongodb+srv connection strings are not supported yet")
        }
        require(uri.startsWith("mongodb://")) { "Only mongodb:// connection strings are supported" }

        val withoutScheme = uri.removePrefix("mongodb://")
        val queryStart = withoutScheme.indexOf('?')
        if (queryStart >= 0 && queryStart < withoutScheme.lastIndex) {
            throw UnsupportedOperationException("MongoDB connection string options are not supported yet")
        }

        val withoutQuery = if (queryStart >= 0) withoutScheme.substring(0, queryStart) else withoutScheme
        val pathStart = withoutQuery.indexOf('/')
        val authority = if (pathStart >= 0) withoutQuery.substring(0, pathStart) else withoutQuery
        val database =
            if (pathStart >= 0) {
                withoutQuery.substring(pathStart + 1).takeIf { it.isNotBlank() }
            } else {
                null
            }

        require(authority.isNotBlank()) { "MongoDB connection string must contain at least one host" }
        if ('@' in authority) {
            throw UnsupportedOperationException("MongoDB authentication in connection strings is not supported yet")
        }

        return MongoConnectionString(
            hosts = authority.split(',').map(::parseHost),
            database = database
        )
    }

    private fun parseHost(value: String): MongoHost {
        require(value.isNotBlank()) { "MongoDB host entries cannot be blank" }

        if (value.startsWith('[')) {
            val closingBracket = value.indexOf(']')
            require(closingBracket > 1) { "Invalid bracketed MongoDB host: $value" }
            val host = value.substring(1, closingBracket)
            val port =
                when {
                    closingBracket == value.lastIndex -> DefaultMongoPort
                    value[closingBracket + 1] == ':' -> parsePort(value.substring(closingBracket + 2))
                    else -> throw IllegalArgumentException("Invalid bracketed MongoDB host: $value")
                }
            return MongoHost(host, port)
        }

        val colonCount = value.count { it == ':' }
        if (colonCount > 1) {
            throw IllegalArgumentException("IPv6 MongoDB hosts must use bracket notation")
        }

        val separator = value.lastIndexOf(':')
        return if (separator < 0) {
            MongoHost(value, DefaultMongoPort)
        } else {
            MongoHost(value.substring(0, separator), parsePort(value.substring(separator + 1)))
        }
    }

    private fun parsePort(value: String): Int {
        val port = value.toIntOrNull() ?: throw IllegalArgumentException("MongoDB port must be a number")
        require(port in 1..65535) { "MongoDB port must be between 1 and 65535" }
        return port
    }
}
