package data.parser

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal val allStatValues = Int.MIN_VALUE..Int.MAX_VALUE

internal data class CsdLookupRow(
    // One range per lookup argument, in directive argument order.
    val keys: List<IntRange>,
    val text: String,
)

internal enum class CsdLookupVariants { DEFAULT, ALSO_TEMPLATE, TEMPLATE_ONLY }

internal sealed interface CsdDirective {
    val arity: Int

    data class Number(
        val multiplier: Int = 1,
        val divisor: Int = 1,
        val addend: Int = 0,
        val decimalPlaces: Int? = null,
        val fixed: Boolean = false,
        val reciprocal: Boolean = false,
    ) : CsdDirective {
        override val arity = 1

        fun apply(value: BigDecimal): BigDecimal {
            val base = if (reciprocal) BigDecimal.ONE.divide(value, MathContext.DECIMAL128) else value
            val result = base.multiply(multiplier.toBigDecimal())
                .divide(divisor.toBigDecimal(), MathContext.DECIMAL128)
                .add(addend.toBigDecimal())
            return decimalPlaces?.let {
                result.setScale(it, if (it == 0) RoundingMode.DOWN else RoundingMode.HALF_EVEN)
            } ?: result
        }
    }

    data class Lookup(
        override val arity: Int,
        val trailingLiteral: String? = null,
        val variants: CsdLookupVariants = CsdLookupVariants.DEFAULT,
        val rows: () -> List<CsdLookupRow>,
    ) : CsdDirective

    // Metadata, not part of the trade matcher (e.g. a reminder or canonical line index).
    data class Metadata(override val arity: Int) : CsdDirective
}

internal class CsdRuleEngine(
    private val registry: Map<String, CsdDirective>,
    private val report: (String) -> Unit = {},
) {
    internal data class Matcher(
        val rule: StatDescriptionRule,
        val text: String,
        val domains: List<List<IntRange>>,
        val lookupSlots: Set<Int>,
        val keySlots: Set<Int>,
        val placeholderSlots: Set<Int>,
        val numberTransforms: List<Pair<Int, CsdDirective.Number>>,
    )

    private data class Instruction(val directive: CsdDirective, val args: List<Int>)
    private data class Variant(
        val domains: List<List<IntRange>>,
        val labels: Map<Int, String> = emptyMap(),
        val keySlots: Set<Int> = emptySet(),
    )

    fun hasLookups(rules: List<StatDescriptionRule>) = rules.any { rule ->
        rule.directiveTokens.any { registry[it] is CsdDirective.Lookup }
    }

    fun hasLookupVariants(rule: StatDescriptionRule, mode: CsdLookupVariants) = rule.directiveTokens.any {
        (registry[it] as? CsdDirective.Lookup)?.variants == mode
    }

    fun legacyProjection(rule: StatDescriptionRule): String? = evaluate(rule) {
        require(instructions(rule).none { it.directive is CsdDirective.Lookup }) { "legacy projection cannot resolve a lookup" }
        if (rule.conditions.any { it.bounds == null }) {
            // One shipped legacy numeric row uses `1|99#`. Preserve its old text without using
            // the malformed condition for value selection or dynamic pairing.
            report("${rule.location}: invalid stat condition: ${rule.conditions.filter { it.bounds == null }} (legacy text only)")
        }
        legacyMatcherText(rule.template)
    }

    fun tradeMatchers(rule: StatDescriptionRule): List<Matcher> = evaluate(rule) {
        val instructions = instructions(rule)
        val lookups = instructions.filter { it.directive is CsdDirective.Lookup }
        require(rule.conditions.none { it.bounds == null }) { "invalid stat condition: ${rule.conditions}" }
        val template = CsdTemplate(rule.template)
        require(template.slots.all { it in rule.conditions.indices }) { "placeholder refers to a nonexistent stat" }
        require(template.supportsMatcherFormats) { "unsupported matcher placeholder format" }
        val initial = Variant(rule.conditions.map { it.intersect(allStatValues) })
        val variants = lookups.fold(listOf(initial)) { variants, instruction ->
            val lookup = instruction.directive as CsdDirective.Lookup
            val rows = lookup.rows()
            require(rows.isNotEmpty()) { "empty lookup for '${lookupName(lookup)}'" }
            variants.flatMap { variant ->
                val matches = rows.mapNotNull { row -> bind(variant, instruction.args, row, rule.location) }
                if (matches.isEmpty()) report("${rule.location}: no '${lookupName(lookup)}' row for ${variant.domains}")
                matches
            }
        }
        val conflicting = if (lookups.isEmpty()) emptyList() else {
            val index = CsdDomainIndex(variants, Variant::domains)
            variants.filter { variant ->
                index.candidates(variant.domains).any { other ->
                    other.labels != variant.labels && domainsOverlap(other.domains, variant.domains)
                }
            }
        }
        if (conflicting.isNotEmpty()) report("${rule.location}: overlapping lookup rows return different text")
        (variants - conflicting.toSet()).distinct().map { variant ->
            Matcher(
                rule, template.renderMatcher(variant.labels),
                variant.domains, variant.labels.keys, variant.keySlots,
                template.slots - variant.labels.keys,
                instructions.mapNotNull { (directive, args) ->
                    (directive as? CsdDirective.Number)?.let { args[0] to it }
                },
            )
        }
    }.orEmpty()

    /** Render a single selected rule with concrete values (not a matcher or a tooltip). */
    fun displayText(rule: StatDescriptionRule, values: List<Int>): String? = evaluate(rule) {
        require(values.size == rule.conditions.size) { "expected ${rule.conditions.size} stat values" }
        if (rule.conditions.any { it.bounds == null }) error("invalid stat condition")
        if (rule.conditions.zip(values).any { (condition, value) -> !condition.contains(value) }) return@evaluate null
        val rendered = values.map { it.toBigDecimal() }.toMutableList()
        val labels = mutableMapOf<Int, String>()
        val formats = mutableMapOf<Int, CsdDirective.Number>()
        instructions(rule).forEach { (directive, args) ->
            when (directive) {
                is CsdDirective.Metadata -> Unit
                is CsdDirective.Number -> {
                    rendered[args[0]] = directive.apply(rendered[args[0]])
                    formats[args[0]] = directive
                }
                is CsdDirective.Lookup -> {
                    val matches = directive.rows().filter { row ->
                        row.keys.size == args.size && row.keys.zip(args).all { (range, index) -> values[index] in range }
                    }.map { it.text }.distinct()
                    labels[args[0]] = matches.singleOrNull()?.takeUnless { it.isBlank() }
                        ?: error("missing or ambiguous '${lookupName(directive)}' row for ${args.map(values::get)}")
                }
            }
        }
        CsdTemplate(rule.template).renderDisplay { index, format ->
            labels[index] ?: rendered[index].let { value ->
                require(format in supportedFormats) { "unsupported placeholder format '$format'" }
                val number = if (formats[index]?.fixed == true) value.toPlainString() else value.stripTrailingZeros().toPlainString()
                if (format.startsWith('+') && value.signum() >= 0) "+$number" else number
            }
        }
    }

    private fun instructions(rule: StatDescriptionRule): List<Instruction> = buildList {
        val tokens = rule.directiveTokens
        var position = 0
        while (position < tokens.size) {
            val name = tokens[position++]
            val handler = registry[name] ?: error("unsupported directive '$name' (${tokens.drop(position - 1).joinToString(" ")})")
            require(tokens.size - position >= handler.arity) { "'$name' requires ${handler.arity} arguments" }
            val args = tokens.subList(position, position + handler.arity)
            position += handler.arity
            if (handler is CsdDirective.Metadata) {
                require(args.none { it in registry }) { "missing argument for '$name'" }
                continue
            }
            val literal = (handler as? CsdDirective.Lookup)?.trailingLiteral
            val statArgs = if (literal == null) args else {
                require(args.last() == literal) { "'$name' requires trailing literal '$literal'" }
                args.dropLast(1)
            }
            add(Instruction(handler, statArgs.map {
                val index = it.toIntOrNull()
                require(index != null && index in 1..rule.conditions.size) { "invalid stat index '$it' for '$name'" }
                index - 1
            }))
        }
    }

    private fun bind(variant: Variant, indexes: List<Int>, row: CsdLookupRow, location: CsdLocation): Variant? {
        require(indexes.size == row.keys.size) { "lookup row has incorrect key count" }
        val domains = variant.domains.toMutableList()
        indexes.zip(row.keys).forEach { (index, key) ->
            domains[index] = domains[index].mapNotNull { intersect(it, key) }
            if (domains[index].isEmpty()) return null
        }
        if (row.text.isBlank()) {
            report("$location: empty lookup text for ${row.keys}")
            return null
        }
        return Variant(domains, variant.labels + (indexes[0] to row.text), variant.keySlots + indexes)
    }

    private fun lookupName(lookup: CsdDirective.Lookup): String = registry.entries.first { it.value === lookup }.key

    private inline fun <T> evaluate(rule: StatDescriptionRule, block: () -> T): T? = try {
        block()
    } catch (error: IllegalArgumentException) {
        report("${rule.location}: ${error.message}")
        null
    } catch (error: IllegalStateException) {
        report("${rule.location}: ${error.message}")
        null
    } catch (error: ArithmeticException) {
        report("${rule.location}: ${error.message}")
        null
    } catch (error: IndexOutOfBoundsException) {
        report("${rule.location}: placeholder refers to a nonexistent stat")
        null
    }
}

internal fun intersect(left: IntRange, right: IntRange): IntRange? =
    (maxOf(left.first, right.first)..minOf(left.last, right.last)).takeUnless { it.isEmpty() }

internal fun domainsOverlap(left: List<List<IntRange>>, right: List<List<IntRange>>): Boolean =
    left.size == right.size && left.zip(right).all { (a, b) ->
        a.any { x -> b.any { y -> intersect(x, y) != null } }
    }

private fun List<IntRange>.pointValue(): Int? = singleOrNull()?.let { range ->
    range.first.takeIf { it == range.last }
}

// A concrete value can only overlap rows with the same value or with a non-point domain.
// This avoids scanning thousands of unrelated passive hashes for each lookup variant.
internal class CsdDomainIndex<T>(private val rows: List<T>, domains: (T) -> List<List<IntRange>>) {
    private data class Slot(val points: Map<Int, List<Int>>, val ranges: List<Int>)

    private val slots = rows.firstOrNull()?.let { row ->
        domains(row).indices.map { slot ->
            val byValue = rows.indices.groupBy { domains(rows[it])[slot].pointValue() }
            Slot(byValue.filterKeys { it != null }.mapKeys { requireNotNull(it.key) }, byValue[null].orEmpty())
        }
    }.orEmpty()

    fun candidates(domains: List<List<IntRange>>): List<T> {
        val best = domains.mapIndexedNotNull { slot, domain ->
            val value = domain.pointValue() ?: return@mapIndexedNotNull null
            val index = slots.getOrNull(slot) ?: return@mapIndexedNotNull null
            index.points[value].orEmpty() to index.ranges
        }.minByOrNull { (points, ranges) -> points.size + ranges.size } ?: return rows
        return if (best.second.isEmpty()) best.first.map(rows::get)
        else (best.first + best.second).sorted().map(rows::get)
    }
}

private val placeholder = Regex("""(?<![>{])(\+?)\{(\d*)(?::([^}]+))?}""")
private val supportedFormats = setOf("", "d", "+d", "+")
private val markup = Regex("<[^>]+>")
private val brace = Regex("[{}]+")
private val link = Regex("""\[([^\]|]+\|)?([^\]]+)]""")
private val legacyLink = Regex("""\[([^|]+\|)?([^]]+)]""")
private val legacyPlaceholder = Regex("""\+?\{\d*(?::(?:\+?d|\+))?}""")

internal fun legacyMatcherText(raw: String): String {
    if (raw.startsWith("[DNT]")) return raw
    return raw.unescapeCsd().replace(legacyPlaceholder, "#")
        .replace(brace, "").replace(markup, "").replace(legacyLink, "\$2")
}

private class CsdTemplate(raw: String) {
    private val text = raw.unescapeCsd().replace(link, "\$2")
    private val parts = buildList {
        var previous = -1
        placeholder.findAll(text).forEach { match ->
            val index = match.groupValues[2].toIntOrNull() ?: previous + 1
            previous = index
            add(Part(match.range, index, match.groupValues[3], match.groupValues[1]))
        }
    }
    val slots get() = parts.mapTo(mutableSetOf()) { it.index }
    val supportsMatcherFormats get() = parts.all { it.format in supportedFormats }

    private data class Part(val range: IntRange, val index: Int, val format: String, val prefix: String)

    fun renderMatcher(labels: Map<Int, String>): String = render { labels[it.index] ?: "#" }

    fun renderDisplay(value: (Int, String) -> String): String = render { it.prefix + value(it.index, it.format) }

    private fun render(value: (Part) -> String): String = buildString {
        var start = 0
        parts.forEach { part ->
            append(clean(text.substring(start, part.range.first)))
            append(value(part))
            start = part.range.last + 1
        }
        append(clean(text.substring(start)))
    }

    private fun clean(literal: String) = literal.replace(markup, "").replace(brace, "")
}

private fun String.unescapeCsd(): String = buildString(length) {
    var i = 0
    while (i < this@unescapeCsd.length) {
        val char = this@unescapeCsd[i++]
        if (char != '\\' || i == this@unescapeCsd.length) {
            append(char)
            continue
        }
        when (val next = this@unescapeCsd[i++]) {
            'n' -> append('\n')
            't' -> append('\t')
            'r' -> append('\r')
            '\\', '"' -> append(next)
            else -> append('\\').append(next)
        }
    }
}
