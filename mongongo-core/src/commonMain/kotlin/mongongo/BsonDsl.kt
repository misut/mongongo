package mongongo

import kotlin.reflect.KProperty1

public fun filter(block: BsonFilterBuilder.() -> Unit): BsonDocument =
    BsonFilterBuilder().apply(block).build()

public fun update(block: BsonUpdateBuilder.() -> Unit): BsonDocument =
    BsonUpdateBuilder().apply(block).build()

public class BsonFilterBuilder {
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
        name eq value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.ne(value: V) {
        name ne value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.gt(value: V) {
        name gt value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.gte(value: V) {
        name gte value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.lt(value: V) {
        name lt value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.lte(value: V) {
        name lte value
    }

    public infix fun <T : Any, V> KProperty1<T, V>.inList(values: Iterable<V>) {
        name inList values
    }

    public infix fun <T : Any, V> KProperty1<T, V>.nin(values: Iterable<V>) {
        name nin values
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
        not(filter(block))
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
