package data.parser

import com.google.gson.annotations.SerializedName
import util.fromJson
import java.io.File

internal fun gameCsdEngine(tables: File, report: (String) -> Unit): CsdRuleEngine =
    CsdRuleEngine(gameCsdDirectives(tables), report)

internal fun gameCsdDirectives(tables: File): Map<String, CsdDirective> = buildMap {
    fun number(
        name: String,
        multiplier: Int = 1,
        divisor: Int = 1,
        addend: Int = 0,
        decimalPlaces: Int? = null,
        fixed: Boolean = false,
        reciprocal: Boolean = false,
    ) {
        put(name, CsdDirective.Number(multiplier, divisor, addend, decimalPlaces, fixed, reciprocal))
    }

    fun lookup(name: String, arity: Int, load: () -> List<CsdLookupRow>) {
        // Cache missing/unreadable tables too. Include the actual export path in the diagnostic.
        val result by lazy {
            try {
                Result.success(load())
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        put(name, CsdDirective.Lookup(arity) {
            result.getOrElse { error("$name: ${it.message}") }
        })
    }

    put("canonical_line", CsdDirective.Metadata(0))
    put("markup", CsdDirective.Metadata(0))
    put("canonical_stat", CsdDirective.Metadata(1))
    put("reminderstring", CsdDirective.Metadata(1))

    number("negate", multiplier = -1)
    number("double", multiplier = 2)
    number("negate_and_double", multiplier = -2)
    number("times_twenty", multiplier = 20)
    number("times_one_point_five", multiplier = 3, divisor = 2)
    number("30%_of_value", multiplier = 30, divisor = 100)
    number("60%_of_value", multiplier = 60, divisor = 100)
    number("multiply_by_four", multiplier = 4)
    number("multiply_by_ten", multiplier = 10)
    number("multiply_by_one_hundred", multiplier = 100)
    number("deciseconds_to_seconds", divisor = 10)
    number("milliseconds_to_seconds_halved", divisor = 500)
    number("locations_to_metres", divisor = 10)
    number("old_leech_percent", divisor = 5)
    number("old_leech_permyriad", divisor = 500)
    number("divide_by_one_hundred_and_negate", multiplier = -1, divisor = 100)
    number("divide_by_twenty_then_double_0dp", multiplier = 2, divisor = 20, decimalPlaces = 0)
    number("permyriad_per_minute_to_%_per_second", divisor = 6000, decimalPlaces = 1)
    number("multiplicative_damage_modifier", addend = 100)
    number("multiplicative_permyriad_damage_modifier", divisor = 100, addend = 100)
    number("plus_two_hundred", addend = 200)
    number("invert_chance", multiplier = -1, addend = 100)
    number("one_hundred_divide_by_value", multiplier = 100, decimalPlaces = 2, reciprocal = true)
    number("add_one", addend = 1)
    number("subtract_one", addend = -1)
    mapOf(
        "divide_by_four" to 4, "divide_by_five" to 5, "divide_by_six" to 6, "divide_by_twelve" to 12,
        "divide_by_three" to 3, "divide_by_twenty" to 20, "divide_by_fifty" to 50,
        "divide_by_one_thousand" to 1000,
    ).forEach { (name, divisor) -> number(name, divisor = divisor) }
    mapOf(
        "divide_by_one_hundred" to 100, "milliseconds_to_seconds" to 1000,
        "divide_by_ten" to 10, "per_minute_to_per_second" to 60,
    ).forEach { (name, divisor) ->
        number(name, divisor = divisor, decimalPlaces = if (divisor == 60) 1 else null)
        number("${name}_0dp", divisor = divisor, decimalPlaces = 0)
        for (dp in 1..2) {
            number("${name}_${dp}dp", divisor = divisor, decimalPlaces = dp, fixed = true)
            number("${name}_${dp}dp_if_required", divisor = divisor, decimalPlaces = dp)
        }
    }
    number("divide_by_two_0dp", divisor = 2, decimalPlaces = 0)
    number("divide_by_fifteen_0dp", divisor = 15, decimalPlaces = 0)
    number("divide_by_ten_thousand_1dp", divisor = 10000, decimalPlaces = 1, fixed = true)

    lookup("alternate_tree_version_name", 1) {
        tables.readRows<IndexedName>("AlternateTreeVersions").mapNotNull {
            val index = requireNotNull(it.index) { "missing _index in AlternateTreeVersions" }
            it.name?.takeIf(String::isNotBlank)?.let { name -> CsdLookupRow(listOf(point(index)), name) }
        }
    }
    lookup("alternate_tree_version_keystone", 3) {
        tables.readRows<AlternateTreeKeystone>("AlternateTreeVersionKeystones")
            .groupBy {
                val version = requireNotNull(it.version) { "missing Version in AlternateTreeVersionKeystones" }
                val stat = requireNotNull(it.stat) { "missing StatValue in AlternateTreeVersionKeystones" }
                version to stat
            }.flatMap { (key, rows) ->
                val (version, stat) = key
                val revisions = rows.groupBy {
                    requireNotNull(it.revision) { "missing InternalVersion in AlternateTreeVersionKeystones" }
                }.toSortedMap().entries.toList()
                revisions.flatMapIndexed { index, (revision, entries) ->
                    // A revision remains active until the next name for this (faction, keystone).
                    // This is also how older CSDs express `0` / `1|#` for the same person slot.
                    val range = revision..(revisions.getOrNull(index + 1)?.key?.minus(1) ?: Int.MAX_VALUE)
                    entries.map {
                        CsdLookupRow(listOf(point(stat), point(version), range), it.conqueror.orEmpty())
                    }
                }
            }
    }
    fun indexableSupport(name: String, table: String) {
        // The second argument is the level, which does not affect the support name.
        lookup(name, 2) {
            tables.readRows<IndexableSupport>(table).map {
                val index = requireNotNull(it.index) { "missing Index in $table" }
                CsdLookupRow(listOf(point(index), allStatValues), it.name.orEmpty())
            }
        }
    }
    indexableSupport("display_indexable_support", "IndexableSupportGems")
    indexableSupport("display_indexable_non_active_support", "IndexableNonActiveSupportGems")

    lookup("pelagic_pearl_slot", 1) {
        val strings = tables.readRows<PearlClientString>("ClientStrings").associate { it.id to it.text }
        listOf("Helmet", "Gloves", "Boots", "PassiveTree").mapIndexed { index, slot ->
            val id = "PelagicPearlSlotType$slot"
            val text = strings[id]?.takeIf(String::isNotBlank)
            requireNotNull(text) { "missing text for $id in ${tables.resolve("ClientStrings.json")}" }
            CsdLookupRow(listOf(point(index + 1)), text)
        }
    }
}

private fun point(value: Int) = value..value

private inline fun <reified T> File.readRows(table: String): List<T> {
    val file = resolve("$table.json")
    require(file.isFile) { "missing table $file; rerun the game data export" }
    return file.reader(Charsets.UTF_8).use { fromJson(it) }
}

private data class IndexedName(
    @SerializedName("_index") val index: Int?,
    @SerializedName("Name") val name: String?,
)

private data class AlternateTreeKeystone(
    @SerializedName("Version") val version: Int?,
    @SerializedName("StatValue") val stat: Int?,
    @SerializedName("InternalVersion") val revision: Int?,
    @SerializedName("Conqueror") val conqueror: String?,
)

private data class IndexableSupport(
    @SerializedName("Index") val index: Int?,
    @SerializedName("Name") val name: String?,
)

private data class PearlClientString(
    @SerializedName("Id") val id: String?,
    @SerializedName("Text") val text: String?,
)
