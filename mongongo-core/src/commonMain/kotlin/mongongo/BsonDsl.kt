@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package mongongo

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

public fun filter(block: BsonFilterBuilder.() -> Unit): BsonDocument =
    BsonFilterBuilder().apply(block).build()

public fun <T : Any> filter(serializer: KSerializer<T>, block: TypedBsonFilterBuilder<T>.() -> Unit): BsonDocument =
    TypedBsonFilterBuilder<T>(serializer).apply(block).build()

public inline fun <reified T : Any> typedFilter(noinline block: TypedBsonFilterBuilder<T>.() -> Unit): BsonDocument =
    filter(serializer<T>(), block)

public fun update(block: BsonUpdateBuilder.() -> Unit): BsonDocument =
    BsonUpdateBuilder().apply(block).build()

public class BsonFilterBuilder internal constructor(
    @PublishedApi internal val fieldNames: BsonFieldNameResolver
) {
    public constructor() : this(KotlinPropertyFieldNameResolver)

    private val values = linkedMapOf<String, BsonValue>()

    public fun build(): BsonDocument = BsonDocument(values.toMap())

    public infix fun String.eq(value: Any?) {
        setField(this, value.toBsonValue())
    }

    public infix fun String.ne(value: Any?) {
        setFieldOperator(this, "\$ne", value.toBsonValue())
    }

    public infix fun String.gt(value: Any?) {
        setFieldOperator(this, "\$gt", value.toBsonValue())
    }

    public infix fun String.gte(value: Any?) {
        setFieldOperator(this, "\$gte", value.toBsonValue())
    }

    public infix fun String.lt(value: Any?) {
        setFieldOperator(this, "\$lt", value.toBsonValue())
    }

    public infix fun String.lte(value: Any?) {
        setFieldOperator(this, "\$lte", value.toBsonValue())
    }

    public infix fun String.inList(values: Iterable<Any?>) {
        setFieldOperator(this, "\$in", values.toBsonArray())
    }

    public infix fun String.nin(values: Iterable<Any?>) {
        setFieldOperator(this, "\$nin", values.toBsonArray())
    }

    public infix fun <T : Any, V> KProperty1<T, V>.eq(value: V) {
        fieldNames.resolve(this, valueDescriptor = null) eq value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.ne(value: V) {
        fieldNames.resolve(this, valueDescriptor = null) ne value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.gt(value: V) {
        fieldNames.resolve(this, valueDescriptor = null) gt value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.gte(value: V) {
        fieldNames.resolve(this, valueDescriptor = null) gte value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.lt(value: V) {
        fieldNames.resolve(this, valueDescriptor = null) lt value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.lte(value: V) {
        fieldNames.resolve(this, valueDescriptor = null) lte value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.inList(values: Iterable<V>) {
        fieldNames.resolve(this, valueDescriptor = null) inList values
    }

    public infix fun <T : Any, V> KProperty1<T, V>.nin(values: Iterable<V>) {
        fieldNames.resolve(this, valueDescriptor = null) nin values
    }

    public fun and(vararg filters: BsonDocument) {
        addLogical("\$and", filters.toList())
    }

    public fun or(vararg filters: BsonDocument) {
        addLogical("\$or", filters.toList())
    }

    public fun not(filter: BsonDocument) {
        addLogical("\$nor", listOf(filter))
    }

    public fun not(block: BsonFilterBuilder.() -> Unit) {
        not(BsonFilterBuilder(fieldNames).apply(block).build())
    }

    private fun setField(name: String, value: BsonValue) {
        val existing = values[name]
        if (existing == null) {
            values[name] = value
        } else {
            setFieldOperator(name, "\$eq", value)
        }
    }

    private fun setFieldOperator(name: String, operator: String, value: BsonValue) {
        val existing = values[name]
        val operators =
            when {
                existing == null -> linkedMapOf()
                existing is BsonDocument && existing.values.keys.all { it.startsWith("\$") } ->
                    linkedMapOf<String, BsonValue>().also { it.putAll(existing.values) }
                else -> linkedMapOf("\$eq" to existing)
            }
        operators[operator] = value
        values[name] = BsonDocument(operators)
    }

    private fun addLogical(operator: String, filters: List<BsonDocument>) {
        require(filters.isNotEmpty()) { "$operator requires at least one filter" }
        val existing = values[operator] as? BsonArray
        val clauses = existing?.values.orEmpty() + filters
        values[operator] = BsonArray(clauses)
    }
}

public class TypedBsonFilterBuilder<T : Any> internal constructor(
    @PublishedApi internal val delegate: BsonFilterBuilder,
    @PublishedApi internal val fieldNames: BsonFieldNameResolver
) {
    internal constructor(serializer: KSerializer<*>) : this(SerializerBsonFieldNameResolver(serializer.descriptor))

    internal constructor(fieldNames: BsonFieldNameResolver) : this(BsonFilterBuilder(fieldNames), fieldNames)

    public fun build(): BsonDocument = delegate.build()

    public infix fun String.eq(value: Any?) {
        val field = this
        delegate.run { field eq value }
    }

    public infix fun String.ne(value: Any?) {
        val field = this
        delegate.run { field ne value }
    }

    public infix fun String.gt(value: Any?) {
        val field = this
        delegate.run { field gt value }
    }

    public infix fun String.gte(value: Any?) {
        val field = this
        delegate.run { field gte value }
    }

    public infix fun String.lt(value: Any?) {
        val field = this
        delegate.run { field lt value }
    }

    public infix fun String.lte(value: Any?) {
        val field = this
        delegate.run { field lte value }
    }

    public infix fun String.inList(values: Iterable<Any?>) {
        val field = this
        delegate.run { field inList values }
    }

    public infix fun String.nin(values: Iterable<Any?>) {
        val field = this
        delegate.run { field nin values }
    }

    public inline infix fun <reified V> KProperty1<T, V>.eq(value: V) {
        fieldNames.resolve(this, valueDescriptor = serialDescriptorProvider<V>()) eq value
    }

    public inline infix fun <reified V> KProperty1<T, V>.ne(value: V) {
        fieldNames.resolve(this, valueDescriptor = serialDescriptorProvider<V>()) ne value
    }

    public inline infix fun <reified V> KProperty1<T, V>.gt(value: V) {
        fieldNames.resolve(this, valueDescriptor = serialDescriptorProvider<V>()) gt value
    }

    public inline infix fun <reified V> KProperty1<T, V>.gte(value: V) {
        fieldNames.resolve(this, valueDescriptor = serialDescriptorProvider<V>()) gte value
    }

    public inline infix fun <reified V> KProperty1<T, V>.lt(value: V) {
        fieldNames.resolve(this, valueDescriptor = serialDescriptorProvider<V>()) lt value
    }

    public inline infix fun <reified V> KProperty1<T, V>.lte(value: V) {
        fieldNames.resolve(this, valueDescriptor = serialDescriptorProvider<V>()) lte value
    }

    public inline infix fun <reified V> KProperty1<T, V>.inList(values: Iterable<V>) {
        fieldNames.resolve(this, valueDescriptor = serialDescriptorProvider<V>()) inList values
    }

    public inline infix fun <reified V> KProperty1<T, V>.nin(values: Iterable<V>) {
        fieldNames.resolve(this, valueDescriptor = serialDescriptorProvider<V>()) nin values
    }

    public fun and(vararg filters: BsonDocument) {
        delegate.and(*filters)
    }

    public fun or(vararg filters: BsonDocument) {
        delegate.or(*filters)
    }

    public fun not(filter: BsonDocument) {
        delegate.not(filter)
    }

    public fun not(block: TypedBsonFilterBuilder<T>.() -> Unit) {
        not(TypedBsonFilterBuilder<T>(fieldNames).apply(block).build())
    }
}

internal fun <T : Any> propertyRejectingFilter(block: TypedBsonFilterBuilder<T>.() -> Unit): BsonDocument =
    TypedBsonFilterBuilder<T>(UnsupportedBsonFieldNameResolver).apply(block).build()

@PublishedApi
internal fun interface BsonFieldNameResolver {
    fun resolve(property: KProperty1<*, *>, valueDescriptor: (() -> SerialDescriptor?)?): String
}

private object KotlinPropertyFieldNameResolver : BsonFieldNameResolver {
    override fun resolve(property: KProperty1<*, *>, valueDescriptor: (() -> SerialDescriptor?)?): String =
        property.name
}

private object UnsupportedBsonFieldNameResolver : BsonFieldNameResolver {
    override fun resolve(property: KProperty1<*, *>, valueDescriptor: (() -> SerialDescriptor?)?): String =
        error(
            "Typed property filter '${property.name}' requires a kotlinx.serialization-backed collection " +
                "or an explicit serializer via filter(serializer) or typedFilter<T>(); use a string field name for raw BSON filters"
        )
}

private class SerializerBsonFieldNameResolver(
    private val descriptor: SerialDescriptor
) : BsonFieldNameResolver {
    override fun resolve(property: KProperty1<*, *>, valueDescriptor: (() -> SerialDescriptor?)?): String {
        val directMatches = descriptor.elementIndicesMatching(property.name)
        require(directMatches.size <= 1) {
            "Property '${property.name}' maps ambiguously to BSON field '${property.name}' through serializer " +
                descriptor.serialName
        }
        if (directMatches.size == 1) {
            return descriptor.getElementName(directMatches.single())
        }

        val inferredMatches = valueDescriptor?.invoke()?.let(::elementIndicesMatchingValueDescriptor).orEmpty()
        val hintedMatches =
            inferredMatches.filter { index ->
                descriptor.getElementName(index).normalizedFieldName().contains(property.name.normalizedFieldName())
            }
        val matches = if (hintedMatches.isNotEmpty()) hintedMatches else inferredMatches

        require(matches.size == 1) {
            when {
                matches.isEmpty() ->
                    "Property '${property.name}' cannot be mapped through serializer ${descriptor.serialName}; " +
                        "descriptor fields are ${descriptor.elementNamesForMessage()}"
                else ->
                    "Property '${property.name}' maps ambiguously through serializer ${descriptor.serialName}; " +
                        "candidate BSON fields are ${matches.joinToString(prefix = "[", postfix = "]") { descriptor.getElementName(it) }}"
            }
        }

        return descriptor.getElementName(matches.single())
    }

    private fun elementIndicesMatchingValueDescriptor(valueDescriptor: SerialDescriptor): List<Int> =
        (0 until descriptor.elementsCount).filter { index ->
            descriptor.getElementDescriptor(index).matchesValueDescriptor(valueDescriptor)
        }
}

@PublishedApi
internal inline fun <reified V> serialDescriptorProvider(): () -> SerialDescriptor? =
    {
        try {
            serializer<V>().descriptor
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

private fun SerialDescriptor.matchesValueDescriptor(other: SerialDescriptor): Boolean =
    this == other ||
        (serialName == other.serialName && kind == other.kind && isNullable == other.isNullable)

private fun String.normalizedFieldName(): String =
    filter { char -> char.isLetterOrDigit() }.lowercase()

private fun SerialDescriptor.elementIndicesMatching(name: String): List<Int> =
    (0 until elementsCount).filter { index -> getElementName(index) == name }

private fun SerialDescriptor.elementNamesForMessage(): String =
    (0 until elementsCount).joinToString(prefix = "[", postfix = "]") { index -> getElementName(index) }

public class BsonUpdateBuilder {
    private val operators = linkedMapOf<String, LinkedHashMap<String, BsonValue>>()

    public fun build(): BsonDocument =
        BsonDocument(
            operators
                .mapValuesTo(linkedMapOf()) { (_, fields) -> BsonDocument(fields.toMap()) }
        )

    public fun set(name: String, value: Any?) {
        add("\$set", name, value.toBsonValue())
    }

    public fun unset(name: String) {
        add("\$unset", name, BsonString(""))
    }

    public fun inc(name: String, amount: Int) {
        add("\$inc", name, BsonInt32(amount))
    }

    public fun inc(name: String, amount: Long) {
        add("\$inc", name, BsonInt64(amount))
    }

    public fun inc(name: String, amount: Double) {
        add("\$inc", name, BsonDouble(amount))
    }

    public fun push(name: String, value: Any?) {
        add("\$push", name, value.toBsonValue())
    }

    public fun pull(name: String, value: Any?) {
        add("\$pull", name, value.toBsonValue())
    }

    public fun addToSet(name: String, value: Any?) {
        add("\$addToSet", name, value.toBsonValue())
    }

    private fun add(operator: String, name: String, value: BsonValue) {
        operators.getOrPut(operator) { linkedMapOf() }[name] = value
    }
}

internal fun Any?.toBsonValue(): BsonValue =
    when (this) {
        null -> BsonNull
        is BsonValue -> this
        is String -> BsonString(this)
        is Int -> BsonInt32(this)
        is Long -> BsonInt64(this)
        is Double -> BsonDouble(this)
        is Boolean -> BsonBoolean(this)
        is Iterable<*> -> this.toBsonArray()
        is Array<*> -> this.asIterable().toBsonArray()
        else -> error("Unsupported BSON DSL value ${this::class.simpleName ?: this::class.toString()}")
    }

private fun Iterable<*>.toBsonArray(): BsonArray =
    BsonArray(map { value -> value.toBsonValue() })
