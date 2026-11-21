package stat

import data.AptDataRepo

internal data class TradeCsdOrigin(
    val mapper: String,
    val sourceExport: String,
    val targetExport: String,
    val file: String,
    val statIds: List<String>,
    val identifier: String?,
    val sourceLine: Int,
    val targetLine: Int,
)

internal data class TradeCsdCandidate(
    val origin: TradeCsdOrigin,
    val pairs: List<Pair<String, String>>,
)

internal data class TradeCsdAlias(val tradeId: String, val sourceStatIds: List<String>)

internal object TradeStatResolver {
    private val regularId = Regex("""(?!pseudo\.)[a-z_]+\.stat_(\d+)""")

    data class Translation(val string: String, val advanced: String?)
    data class Match(val translation: Translation, val origins: List<TradeCsdOrigin>)
    data class Resolution(
        val status: String,
        val matches: List<Match> = emptyList(),
        val candidates: List<TradeCsdOrigin> = emptyList(),
    ) {
        val accepted get() = if (status == "conflict") emptyList() else matches
    }

    fun hashes(ids: List<String>): List<Long> {
        // Fixed options and server-defined special filters have their own generators.
        // Pseudo aliases alongside normal IDs (e.g. added damage) do not prevent lookup.
        if (ids.any { '|' in it || "timeless_jewel" in it }) return emptyList()
        return ids.mapNotNull {
            regularId.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it <= 0xffffffffL }
        }.distinct()
    }

    fun resolveAliases(
        matcher: AptDataRepo.Stat.Matcher,
        ids: List<String>,
        aliases: List<TradeCsdAlias>,
        candidates: (Long) -> List<TradeCsdCandidate>,
        normalize: (String) -> String,
    ): List<Match> = aliases.filter { it.tradeId in ids }.flatMap { alias ->
        val hash = TradeStatHash.compute(alias.sourceStatIds)
        // A configured equivalence is scoped to this exact CSD group, not every stat
        // with the same English text (nor another group in the same hash bucket).
        val scoped = candidates(hash).filter { it.origin.statIds == alias.sourceStatIds }
        resolve(matcher, listOf(hash), scoped, normalize).accepted
    }

    fun resolve(
        matcher: AptDataRepo.Stat.Matcher,
        hashes: List<Long>,
        candidates: List<TradeCsdCandidate>,
        normalize: (String) -> String,
        includeUnmatchedOrigins: Boolean = false,
    ): Resolution {
        if (hashes.isEmpty()) return Resolution("special")
        if (candidates.isEmpty()) return Resolution("no-hash")

        data class Field(val target: String, val origin: TradeCsdOrigin)
        fun field(text: String): List<Field> {
            val normalized = normalize(text)
            val key = normalized.uppercase()
            return candidates.flatMap { candidate ->
                candidate.pairs.mapNotNull { (source, target) ->
                    if (normalize(source).uppercase() != key) return@mapNotNull null
                    val localized = normalize(target)
                    if (localized.isBlank() || localized.equals(normalized, true)) return@mapNotNull null
                    Field(localized, candidate.origin)
                }
            }.distinct()
        }
        val plain = field(matcher.string)
        val advanced = matcher.advanced?.let(::field)
        val matches = plain.flatMap { base ->
            if (advanced == null) listOf(Match(Translation(base.target, null), listOf(base.origin)))
            else advanced.mapNotNull { detail ->
                // An advanced rule can live in another CSD file, but must describe the same
                // ordered stats in the same export. Never cross-join unrelated stats or locales.
                if (base.origin.mapper != detail.origin.mapper || base.origin.statIds != detail.origin.statIds) {
                    return@mapNotNull null
                }
                Match(Translation(base.target, detail.target), listOf(base.origin, detail.origin).distinct())
            }
        }.groupBy { it.translation }.map { (translation, matches) ->
            Match(translation, matches.flatMap { it.origins }.distinct())
        }
        val conflicting = matches.size > 1 && (
            matches.flatMap { it.origins }.map { it.statIds }.distinct().size > 1 ||
                matches.flatMap { match -> match.origins.map { it.mapper }.distinct() }.let { it.size != it.distinct().size }
            )
        val ruleContexts = candidates.distinctBy { Triple(it.origin.file, it.origin.statIds, it.origin.identifier) }
        val status = when {
            conflicting -> "conflict"
            matches.isEmpty() -> "no-variant"
            ruleContexts.size > 1 || candidates.any { it.pairs.map { pair -> pair.first }.distinct().size > 1 } ||
                hashes.size > 1 -> "id-disambiguated"
            else -> "id"
        }
        return Resolution(status, matches, if (includeUnmatchedOrigins) candidates.map { it.origin } else emptyList())
    }

    fun apply(matcher: AptDataRepo.Stat.Matcher, translation: Translation) = matcher.rawData.deepCopy().apply {
        addProperty("string", translation.string)
        translation.advanced?.let { addProperty("advanced", it) }
    }
}
