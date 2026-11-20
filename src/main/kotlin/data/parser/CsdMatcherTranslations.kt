package data.parser

import data.parser.CsdRuleEngine.Matcher
import kotlin.math.min

internal class CsdMatcherTranslations(
    private val sourceEngine: CsdRuleEngine,
    private val targetEngine: CsdRuleEngine,
    private val report: (String) -> Unit,
) {
    fun pair(
        source: GameStatDescription,
        sourceLanguage: String,
        target: GameStatDescription,
        targetLanguage: String,
    ): List<Pair<String, String>> {
        require(source.statIds == target.statIds)
        val sourceRules = source.rulesByLang[sourceLanguage].orEmpty()
        val targetRules = target.rulesByLang[targetLanguage].orEmpty()
        val dynamic = sourceEngine.hasLookups(sourceRules) || targetEngine.hasLookups(targetRules)
        if (sourceRules.isEmpty() || targetRules.isEmpty()) {
            if (dynamic) report("${source.location}: missing $sourceLanguage or $targetLanguage rules for ${source.id}")
            return emptyList()
        }
        return if (dynamic) {
            pairByDomain(sourceRules, targetRules)
        } else {
            // Named descriptions are reusable format definitions. They were not standalone
            // dictionary entries before this parser; indexing them adds unrelated numeric aliases.
            if (source.identifier != null || target.identifier != null) return emptyList()
            pairLegacy(sourceRules, targetRules, source.uniqueId)
        }
    }

    private fun pairLegacy(
        source: List<StatDescriptionRule>,
        target: List<StatDescriptionRule>,
        id: String,
    ): List<Pair<String, String>> {
        // Keep the established fallback for ordinary localized numeric/literal text. Decode each row
        // independently so an unsupported directive never shifts the pairing of subsequent rows.
        if (source.size != target.size && target.size != 1) {
            report("${source.first().location}: $id has ${source.size} source rows and ${target.size} target rows")
            return emptyList()
        }
        val renderedTarget = target.map(targetEngine::legacyProjection)
        return source.mapIndexedNotNull { index, rule ->
            val from = sourceEngine.legacyProjection(rule) ?: return@mapIndexedNotNull null
            val to = renderedTarget[min(index, renderedTarget.lastIndex)] ?: return@mapIndexedNotNull null
            from to to
        }
    }

    private fun pairByDomain(
        source: List<StatDescriptionRule>,
        target: List<StatDescriptionRule>,
    ): List<Pair<String, String>> {
        val from = source.flatMap(sourceEngine::tradeMatchers)
        val to = target.flatMap(targetEngine::tradeMatchers)
        val resolved = from.map { matcher ->
            val candidates = to.filter { candidate ->
                val keySlots = matcher.keySlots + candidate.keySlots
                val lookupSlots = matcher.lookupSlots + candidate.lookupSlots
                candidate.rule.restrictions == matcher.rule.restrictions &&
                    candidate.placeholderSlots == matcher.placeholderSlots &&
                    candidate.numberTransforms == matcher.numberTransforms &&
                    domainsOverlap(matcher.domains, candidate.domains) &&
                    matcher.domains.indices.all { it in keySlots || matcher.domains[it] == candidate.domains[it] } &&
                    lookupSlots.all { slot ->
                        // A legacy literal row can stand in for a lookup only with a fixed lookup
                        // output key. A generic wildcard row does not identify a localized name.
                        (slot in matcher.lookupSlots || matcher.domains[slot].isPoint()) &&
                            (slot in candidate.lookupSlots || candidate.domains[slot].isPoint())
                    }
            }
            // A localized file can contain both the broad lookup row and legacy literal overrides.
            // Prefer a stricter row only when it covers the whole intersection with the source.
            val best = candidates.filterNot { broad ->
                candidates.any { specific ->
                    moreSpecific(specific, broad) &&
                        contains(specific.domains, overlap(matcher.domains, broad.domains))
                }
            }
            val texts = best.map { it.text }.distinct()
            if (texts.size != 1 || best.none { contains(it.domains, matcher.domains) }) {
                val detail = when {
                    texts.isEmpty() -> "no matching target"
                    texts.size > 1 -> "ambiguous targets $texts"
                    else -> "target covers only part of the source domain"
                }
                report("${matcher.rule.location}: $detail for ${matcher.domains}: ${matcher.text.replace('\n', ' ')}")
                matcher to null
            } else {
                matcher to texts.single()
            }
        }
        // Trade matchers do not carry stat domains. If different source variants render to the
        // same key, only publish it when all variants agree on the target text.
        return resolved.groupBy { (matcher, _) -> matcher.text }.mapNotNull { (text, matches) ->
            val translations = matches.map { it.second }.distinct()
            val translation = translations.singleOrNull()
            if (translation == null) {
                if (translations.size > 1) {
                    report("${matches.first().first.rule.location}: source matcher has conflicting variants: ${text.replace('\n', ' ')}")
                }
                null
            } else {
                text to translation
            }
        }
    }

    private fun List<IntRange>.isPoint() = size == 1 && first().first == first().last

    private fun moreSpecific(candidate: Matcher, other: Matcher): Boolean {
        if (!contains(other.domains, candidate.domains)) return false
        if (candidate.domains != other.domains) return true
        val candidateConditions = candidate.rule.conditions.map { it.intersect(allStatValues) }
        val otherConditions = other.rule.conditions.map { it.intersect(allStatValues) }
        if (candidateConditions != otherConditions) return contains(otherConditions, candidateConditions)
        // Both rules may already constrain the slot to the same value. A literal
        // translation that replaces a lookup at that slot still overrides the default.
        val replacedLookups = other.lookupSlots - candidate.lookupSlots
        return replacedLookups.isNotEmpty() &&
            other.lookupSlots.containsAll(candidate.lookupSlots) &&
            replacedLookups.all { candidate.domains[it].isPoint() }
    }

    private fun contains(outer: List<List<IntRange>>, inner: List<List<IntRange>>) =
        outer.zip(inner).all { (a, b) -> b.all { x -> a.any { y -> x.first in y && x.last in y } } }

    private fun overlap(left: List<List<IntRange>>, right: List<List<IntRange>>) =
        left.zip(right).map { (a, b) -> a.flatMap { x -> b.mapNotNull { y -> intersect(x, y) } } }
}
