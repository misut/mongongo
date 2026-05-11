package mongongo

internal const val DefaultMongoPort = 27017

internal data class MongoHost(
    val hostname: String,
    val port: Int = DefaultMongoPort
)

internal const val ScramSha256Mechanism = "SCRAM-SHA-256"

internal data class MongoCredential(
    val username: String,
    val password: String,
    val authSource: String,
    val mechanism: String = ScramSha256Mechanism
)

internal data class MongoConnectionString(
    val hosts: List<MongoHost>,
    val database: String?,
    val credential: MongoCredential?,
    val tlsEnabled: Boolean,
    val redactedUri: String
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
        val options = parseOptions(if (queryStart >= 0) withoutScheme.substring(queryStart + 1) else null)

        val withoutQuery = if (queryStart >= 0) withoutScheme.substring(0, queryStart) else withoutScheme
        val pathStart = withoutQuery.indexOf('/')
        val authorityWithCredentials = if (pathStart >= 0) withoutQuery.substring(0, pathStart) else withoutQuery
        val database =
            if (pathStart >= 0) {
                withoutQuery.substring(pathStart + 1).takeIf { it.isNotBlank() }
            } else {
                null
            }

        val atSign = authorityWithCredentials.lastIndexOf('@')
        val userInfo = if (atSign >= 0) authorityWithCredentials.substring(0, atSign) else null
        val authority = if (atSign >= 0) authorityWithCredentials.substring(atSign + 1) else authorityWithCredentials

        require(authority.isNotBlank()) { "MongoDB connection string must contain at least one host" }
        val mechanism = options.authMechanism ?: ScramSha256Mechanism
        validateMechanism(mechanism)

        val credential =
            userInfo?.let { credentials ->
                val separator = credentials.indexOf(':')
                require(separator > 0) { "MongoDB authentication credentials must include a username and password" }
                val username = percentDecode(credentials.substring(0, separator), "username")
                require(username.isNotEmpty()) { "MongoDB username cannot be blank" }
                require('\u0000' !in username) { "MongoDB username cannot contain null bytes" }
                val password = percentDecode(credentials.substring(separator + 1), "password")
                MongoCredential(
                    username = username,
                    password = password,
                    authSource = options.authSource ?: database ?: "admin",
                    mechanism = mechanism
                )
            }

        return MongoConnectionString(
            hosts = authority.split(',').map(::parseHost),
            database = database,
            credential = credential,
            tlsEnabled = options.tlsEnabled ?: false,
            redactedUri =
                redactedUri(
                    hosts = authority.split(',').map(::parseHost),
                    database = database,
                    credential = credential,
                    authSource = options.authSource,
                    authMechanism = options.authMechanism,
                    tlsEnabled = options.tlsEnabled ?: false
                )
        )
    }

    private data class ConnectionOptions(
        val authSource: String? = null,
        val authMechanism: String? = null,
        val tlsEnabled: Boolean? = null
    )

    private fun parseOptions(query: String?): ConnectionOptions {
        if (query == null || query.isEmpty()) {
            return ConnectionOptions()
        }

        var authSource: String? = null
        var authMechanism: String? = null
        var tlsEnabled: Boolean? = null
        for (option in query.split('&')) {
            require(option.isNotEmpty()) { "MongoDB connection string options cannot contain blank entries" }
            val separator = option.indexOf('=')
            require(separator > 0) { "MongoDB connection string option must use name=value syntax" }

            val name = percentDecode(option.substring(0, separator), "option name")
            val value = percentDecode(option.substring(separator + 1), "option value")
            when (name.lowercase()) {
                "authsource" -> {
                    require(value.isNotEmpty()) { "MongoDB authSource cannot be empty" }
                    authSource = value
                }
                "authmechanism" -> {
                    validateMechanism(value)
                    authMechanism = value
                }
                "tls",
                "ssl" -> {
                    val parsed = parseTlsOption(name, value)
                    if (tlsEnabled != null && tlsEnabled != parsed) {
                        throw IllegalArgumentException("MongoDB tls and ssl options must not conflict")
                    }
                    tlsEnabled = parsed
                }
                else -> {
                    if (isUnsupportedTlsOption(name)) {
                        throw UnsupportedOperationException(
                            "MongoDB TLS/SSL connection string option $name is not supported yet"
                        )
                    }
                    throw UnsupportedOperationException("MongoDB connection string option $name is not supported yet")
                }
            }
        }

        return ConnectionOptions(authSource = authSource, authMechanism = authMechanism, tlsEnabled = tlsEnabled)
    }

    private fun validateMechanism(value: String) {
        when (value) {
            ScramSha256Mechanism -> Unit
            "SCRAM-SHA-1",
            "MONGODB-X509",
            "PLAIN",
            "GSSAPI",
            "MONGODB-AWS" ->
                throw UnsupportedOperationException("MongoDB authMechanism $value is not supported yet")
            else -> throw UnsupportedOperationException("MongoDB authMechanism $value is not supported yet")
        }
    }

    private fun parseTlsOption(name: String, value: String): Boolean =
        when (value.lowercase()) {
            "false" -> false
            "true" -> true
            else -> throw IllegalArgumentException("MongoDB $name option must be true or false")
        }

    private fun isUnsupportedTlsOption(name: String): Boolean {
        val lowerName = name.lowercase()
        return lowerName.startsWith("tls") || lowerName.startsWith("ssl")
    }

    private fun percentDecode(value: String, label: String): String {
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%') {
                require(index + 2 < value.length) { "Invalid percent-encoded MongoDB $label" }
                val hex = value.substring(index + 1, index + 3)
                val byte = hex.toIntOrNull(radix = 16) ?: throw IllegalArgumentException("Invalid percent-encoded MongoDB $label")
                bytes.add(byte.toByte())
                index += 3
            } else {
                bytes.addAll(character.toString().encodeToByteArray().toList())
                index++
            }
        }
        return bytes.toByteArray().decodeToString()
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

    private fun redactedUri(
        hosts: List<MongoHost>,
        database: String?,
        credential: MongoCredential?,
        authSource: String?,
        authMechanism: String?,
        tlsEnabled: Boolean
    ): String {
        val builder = StringBuilder("mongodb://")
        if (credential != null) {
            builder.append("<credentials>@")
        }
        builder.append(hosts.joinToString(",") { it.toAuthority() })
        if (database != null || authSource != null || authMechanism != null) {
            builder.append("/")
            if (database != null) {
                builder.append(database)
            }
        }

        val options = mutableListOf<String>()
        if (authSource != null) {
            options.add("authSource=$authSource")
        }
        if (authMechanism != null) {
            options.add("authMechanism=$authMechanism")
        }
        if (tlsEnabled) {
            options.add("tls=true")
        }
        if (options.isNotEmpty()) {
            builder.append("?")
            builder.append(options.joinToString("&"))
        }
        return builder.toString()
    }

    private fun MongoHost.toAuthority(): String {
        val host = if (':' in hostname) "[$hostname]" else hostname
        return if (port == DefaultMongoPort) host else "$host:$port"
    }
}
