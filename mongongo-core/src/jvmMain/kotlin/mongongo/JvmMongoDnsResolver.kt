package mongongo

import java.util.Hashtable
import javax.naming.NameNotFoundException
import javax.naming.NamingException
import javax.naming.directory.Attribute
import javax.naming.directory.InitialDirContext

internal actual object SystemMongoDnsResolver : MongoDnsResolver {
    override fun lookupSrv(name: String): List<MongoSrvRecord> {
        val values = lookupAttribute(name = name, attributeName = "SRV")
        return values.map { value ->
            val parts = value.trim().split(Regex("\\s+"))
            require(parts.size == 4) { "Invalid DNS SRV record for $name" }
            MongoSrvRecord(
                hostname = parts[3].trimEnd('.'),
                port = parts[2].toIntOrNull() ?: throw IllegalArgumentException("Invalid DNS SRV port for $name")
            )
        }
    }

    override fun lookupTxt(name: String): List<String> =
        lookupAttribute(name = name, attributeName = "TXT").map(::decodeTxtRecord)

    private fun lookupAttribute(name: String, attributeName: String): List<String> {
        val environment =
            Hashtable<String, String>().apply {
                put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
            }
        return try {
            val context = InitialDirContext(environment)
            try {
                val attribute = context.getAttributes(name, arrayOf(attributeName)).get(attributeName)
                attribute?.values().orEmpty()
            } finally {
                context.close()
            }
        } catch (_: NameNotFoundException) {
            emptyList()
        } catch (exception: NamingException) {
            if (exception.message?.contains("DNS name not found", ignoreCase = true) == true) {
                emptyList()
            } else {
                throw exception
            }
        }
    }

    private fun Attribute.values(): List<String> {
        val values = mutableListOf<String>()
        val allValues = getAll()
        while (allValues.hasMore()) {
            values.add(allValues.next().toString())
        }
        return values
    }

    private fun decodeTxtRecord(value: String): String {
        val trimmed = value.trim()
        if ('"' !in trimmed) {
            return trimmed
        }

        val builder = StringBuilder()
        var inQuote = false
        var escaping = false
        for (character in trimmed) {
            when {
                escaping -> {
                    builder.append(character)
                    escaping = false
                }
                character == '\\' && inQuote -> escaping = true
                character == '"' -> inQuote = !inQuote
                inQuote -> builder.append(character)
            }
        }
        return builder.toString()
    }
}
