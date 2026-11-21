package stat

import com.google.gson.JsonObject
import data.AptDataRepo
import data.EffectMatcherTranslation
import util.removeMarkup

internal object TradeEffectMatchers {
    private val effectId = Regex("""(?!pseudo\.)[a-z_]+\.effect_\d+""")
    private val spacesBeforeNewline = Regex(" +\n")

    private data class StatKey(val ref: String, val ids: Set<String>)
    private data class Proposal(val target: AptDataRepo.Stat, val ids: Set<String>, val matcher: JsonObject)

    data class Decision(val status: String, val ref: String, val ids: Set<String>, val matcher: JsonObject? = null)

    private fun normalize(text: String) = text.removeMarkup()
        .replace("\r\n", "\n")
        .replace("\u200B", "")
        .replace('\u00a0', ' ')
        .replace(spacesBeforeNewline, "\n")
        .trim()

    private fun AptDataRepo.Stat.key() = StatKey(refName, tradeIds.toSet())

    private fun flatten(rows: List<AptDataRepo.BaseStat>) = rows.flatMap {
        when (it) {
            is AptDataRepo.Stat -> listOf(it)
            is AptDataRepo.StatGroup -> it.stats
        }
    }

    fun supplement(
        source: List<AptDataRepo.BaseStat>,
        output: List<AptDataRepo.BaseStat>,
        translations: List<EffectMatcherTranslation>,
        report: (Decision) -> Unit,
    ) {
        val byEnglish = translations.groupBy { normalize(it.english) }
        val localized = flatten(output)
        val byKey = localized.groupBy { it.key() }
        val owners = localized.flatMap { stat ->
            stat.matchers.flatMap { listOfNotNull(it.string, it.advanced) }.map { normalize(it) to stat }
        }.groupBy({ it.first }, { it.second })
        val proposals = mutableListOf<Proposal>()

        for ((key, sources) in flatten(source).groupBy { it.key() }) {
            val ids = key.ids
            if (ids.isEmpty() || !ids.all(effectId::matches)) continue
            val target = byKey[key]?.singleOrNull()
            val original = sources.singleOrNull()
            if (target == null || original == null) {
                report(Decision("ambiguous-stat", key.ref, ids))
                continue
            }

            for ((from, matchers) in original.matchers.groupBy { normalize(it.string) }) {
                if (matchers.none { it.advanced == null }) continue
                val candidates = byEnglish[from].orEmpty()
                if (candidates.isEmpty()) {
                    report(Decision("no-client-matcher", key.ref, ids, matchers.first().rawData))
                    continue
                }
                val matcher = matchers.singleOrNull()
                if (matcher == null || candidates.map { it.gameId }.distinct().size != 1) {
                    report(Decision("ambiguous-client-matcher", key.ref, ids, matchers.first().rawData))
                    continue
                }
                for (to in candidates.map { normalize(it.localized) }.distinct()) {
                    if (to.isBlank() || from.count { it == '#' } != to.count { it == '#' }) {
                        report(Decision("unverified-matcher", key.ref, ids, matcher.rawData))
                        continue
                    }
                    if (to == from || target.matchers.any { normalize(it.string) == to }) continue
                    val data = TradeStatResolver.apply(matcher, TradeStatResolver.Translation(to, null))
                    proposals += Proposal(target, ids, data)
                }
            }
        }

        // Check both existing keys and pending aliases before writing any of them.
        // The renderer looks up a stat by text before checking the modifier type.
        for ((text, matches) in proposals.groupBy { it.matcher["string"].asString }) {
            val proposal = matches.singleOrNull()
            if (proposal == null || owners[text].orEmpty().isNotEmpty()) {
                matches.forEach { report(Decision("matcher-collision", it.target.refName, it.ids, it.matcher)) }
                continue
            }
            val (target, ids, data) = proposal
            target.addMatcher(AptDataRepo.Stat.Matcher(text, null, data))
            report(Decision("added", target.refName, ids, data))
        }
    }
}
