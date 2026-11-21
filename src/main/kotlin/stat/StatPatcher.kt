package stat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import data.AptDataRepo
import data.GameDataRepo
import data.parser.ExtraStat
import util.removeMarkup
import util.fromJson
import java.io.File


object StatPatcher {
    private val outputFile = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data/zh_CN/stats.ndjson")
    private val spacesBeforeNewline = Regex(" +\n")
    private val tradeCsdAliases by lazy {
        File("data_repo/extra/trade_stat_csd_aliases.json").reader(Charsets.UTF_8).use {
            fromJson<List<TradeCsdAlias>>(it)
        }.groupBy { it.tradeId }
    }

    // Some translations collapse distinct English stats into the same matcher.
    // Keep these groups explicit instead of merging every duplicate translation.
    private val trivialMergeRules = listOf(
        setOf(
            "#% increased Elemental Damage",
            "Has #% increased Elemental Damage"
        ),
        setOf(
            "#% reduced Elemental Damage taken while stationary",
            "#% reduced Elemental Damage Taken while stationary"
        ),
        setOf(
            "Cannot be Chilled",
            "Immune to Chill"
        ),
        setOf(
            "Lose # Mana per second",
            "Lose # Mana per Second"
        ),
        setOf(
            "Socketed Gems are supported by Level # Chance to Bleed",
            "Socketed Gems are Supported by Level # Chance To Bleed"
        ),
        setOf(
            "#% increased Physical Damage",
            "Deal no Physical Damage"
        )
    )

    private fun specialFix(text: String): String {
        // 有些词缀在换行前会有空格, 例如: 以阿华纳（阿华纳 - 夏巴夸亚）的名义用 # 名祭品之血浸染 \n范围内的天赋被瓦尔抑制
        // 这里处理一下
        return text
            .removeMarkup()
            .replace('\u00a0', ' ')
            .replace(spacesBeforeNewline, "\n")
    }

    private fun createCombinations(
        cnMatcherNames: Set<String>,
        cnAdvancedNames: Set<String>,
        matcher: AptDataRepo.Stat.Matcher,
    ): List<Pair<String, String>> {
        val translatedMatcherNames = cnMatcherNames
            .filterNot { it.equals(matcher.string, ignoreCase = true) }
            .toSet()
        val translatedAdvancedNames = cnAdvancedNames
            .filterNot { matcher.advanced != null && it.equals(matcher.advanced, ignoreCase = true) }
            .toSet()

        if (translatedMatcherNames.isEmpty()) {
            return emptyList()
        }
        if (matcher.advanced != null && translatedAdvancedNames.isEmpty()) {
            // 如果 matcher 有 advanced, 则必须要有 cnAdvancedNames
            return emptyList()
        }

        val finalCnAdvancedNames = translatedAdvancedNames.ifEmpty {
            // 确保最少有一个, 保证后续笛卡尔积计算正确
            setOf("")
        }
        return translatedMatcherNames.flatMap { cnStatName ->
            finalCnAdvancedNames.map { cnAdvanced -> cnStatName to cnAdvanced }
        }
    }

    internal fun collectExtraStatTranslations(
        extraStats: List<ExtraStat>,
        matcher: AptDataRepo.Stat.Matcher,
    ): Pair<Set<String>, Set<String>> {
        val cnMatcherNames = mutableSetOf<String>()
        val cnAdvancedNames = mutableSetOf<String>()

        extraStats.forEach { extraStat ->
            val index = extraStat.en.indexOfFirst {
                it.string.equals(matcher.string, true)
            }
            if (index < 0) return@forEach

            extraStat.translationSets.forEach { texts ->
                texts.getOrNull(index)?.let { text ->
                    cnMatcherNames.add(text.string)
                    text.advanced?.let(cnAdvancedNames::add)
                }
            }
        }

        return cnMatcherNames to cnAdvancedNames
    }

    internal fun refScopedAliases(extraStats: List<ExtraStat>, matcher: AptDataRepo.Stat.Matcher): List<JsonObject> =
        extraStats.flatMap { extraStat ->
            val index = extraStat.en.indexOfFirst { it.string.equals(matcher.string, true) }
            if (index < 0) return@flatMap emptyList()
            extraStat.translationSets.mapNotNull { texts ->
                val text = texts.getOrNull(index) ?: return@mapNotNull null
                val advanced = if (matcher.advanced != null) {
                    text.advanced?.takeUnless { it.isBlank() || it.equals(matcher.advanced, true) }
                        ?: return@mapNotNull null
                } else null
                if (text.string.isBlank() || text.string.equals(matcher.string, true)) return@mapNotNull null
                TradeStatResolver.apply(matcher, TradeStatResolver.Translation(
                    specialFix(text.string), advanced?.let(::specialFix),
                ))
            }
        }.distinct()

    internal fun doReplace(
        cnMatcherNames: Set<String>,
        cnAdvancedNames: Set<String>,
        stat: AptDataRepo.Stat,
        matcher: AptDataRepo.Stat.Matcher,
    ): Boolean {
        val combinations = createCombinations(cnMatcherNames, cnAdvancedNames, matcher)
        if (combinations.isEmpty()) return false

        val backupMatcherRawData = matcher.rawData.deepCopy()

        // 因为会出现同一个英文名在不同场景下有不同中文翻译的问题, 例如:
        // Adds {0} to {1} Cold Damage 可以被翻译为
        // - 附加 {0} - {1} 基础冰霜伤害 与
        // - 该装备附加 {0} - {1} 基础冰霜伤害
        // 这里的处理方式是:
        // - 如果只有一条中文翻译: 没问题, 直接修改
        // - 如果有多条: 先更新第一条, 然后再添加剩下 matcher
        combinations.forEachIndexed { index, (cnStatName, cnAdvanced) ->
            if (index == 0) {
                matcher.updateString(specialFix(cnStatName))
                matcher.updateAdvancedIfExists(specialFix(cnAdvanced))
            } else {
                val copied = matcher.copy(rawData = backupMatcherRawData.deepCopy())
                copied.updateString(specialFix(cnStatName))
                copied.updateAdvancedIfExists(specialFix(cnAdvanced))
                stat.addMatcher(copied)
            }
        }

        return true
    }

    private fun AptDataRepo.Stat.translateLegacy(
        matcher: AptDataRepo.Stat.Matcher,
        mapper: GameDataRepo.GameDataMapper,
    ): List<JsonObject> {
        val mercenary = rawData["mercenary"]?.asJsonObject
        if (mercenary != null) {
            val translatedName: String
            val tier: Int?
            if (mercenary.has("tier")) {
                val support = mapper.mercenarySupports[refName] ?: return listOf(matcher.rawData)
                translatedName = support.name
                tier = support.tier
            } else {
                translatedName = mapper.mercenarySkills[refName] ?: return listOf(matcher.rawData)
                tier = null
            }

            val tierText = if (tier != null) {
                mapper.clientStrings["ModDescriptionLineTier"]!!
                    .second.text.replace("{0}", tier.toString())
            } else {
                ""
            }
            return listOf(TradeStatResolver.apply(matcher, TradeStatResolver.Translation(
                specialFix(translatedName),
                matcher.advanced?.let { specialFix(translatedName + tierText) },
            )))
        }

        val cnMatcherNames = mapper.preferredStats[matcher.string.uppercase()].orEmpty().toMutableSet()
        val cnAdvancedNames = matcher.advanced?.let { mapper.preferredStats[it.uppercase()] }
            .orEmpty().toMutableSet()

        if (matcher.advanced != null && cnAdvancedNames.isEmpty()) {
            cnMatcherNames.clear()
        }

        val extraStats = buildList {
            addAll(mapper.secondaryStats[refName.uppercase()].orEmpty())
            addAll(mapper.secondaryStats[matcher.string.uppercase()].orEmpty())
        }.distinct()
        val (extraMatcherNames, extraAdvancedNames) = collectExtraStatTranslations(extraStats, matcher)
        cnMatcherNames.addAll(extraMatcherNames)
        cnAdvancedNames.addAll(extraAdvancedNames)

        return createCombinations(cnMatcherNames, cnAdvancedNames, matcher).map { (cnStatName, cnAdvanced) ->
            TradeStatResolver.apply(matcher, TradeStatResolver.Translation(
                specialFix(cnStatName),
                cnAdvanced.takeIf { matcher.advanced != null && it.isNotBlank() }?.let(::specialFix),
            ))
        }
    }

    private fun findReminderTranslation(source: String, translations: Map<String, String>): String? {
        translations[source]?.let { return it }

        // 部分神庙提示只保留完整房间描述的末尾几行。
        val sourceLines = source.lines()
        if (sourceLines.size < 2) return null
        return translations.mapNotNull { (english, chinese) ->
            val englishLines = english.lines()
            if (englishLines.size <= sourceLines.size ||
                englishLines.takeLast(sourceLines.size) != sourceLines
            ) return@mapNotNull null

            val chineseLines = chinese.lines()
            if (chineseLines.size != englishLines.size) return@mapNotNull null
            chineseLines.takeLast(sourceLines.size).joinToString("\n")
        }.distinct().singleOrNull()
    }

    private fun mergeTranslatedStats(
        original: AptDataRepo.Stat,
        translated: List<AptDataRepo.Stat>,
        reminderTexts: Sequence<Map<String, String>>,
    ): AptDataRepo.Stat {
        val translatedMatchers = translated
            .filter { it.hasMatchers() }
            .flatMap { stat -> stat.matchers.map { it.rawData.deepCopy() } }
            .distinctBy { it.toString() }

        return original.deepClone().also { result ->
            if (translatedMatchers.isNotEmpty()) {
                result.replaceMatchers(translatedMatchers)
            }
            // 提示文本独立于 matcher，按 mapper 顺序取第一条有效翻译。
            val source = original.reminderText
            if (!source.isNullOrBlank()) {
                val localized = reminderTexts
                    .mapNotNull { findReminderTranslation(source, it)?.let(::specialFix) }
                    .firstOrNull { it.isNotBlank() && it != source }
                if (localized != null) {
                    result.rawData.addProperty("reminderText", localized)
                }
            }
        }
    }

    private fun mergeTranslatedStats(
        original: AptDataRepo.StatGroup,
        translated: List<AptDataRepo.StatGroup>,
        reminderTexts: Sequence<Map<String, String>>,
    ): AptDataRepo.StatGroup {
        val translatedStats = original.stats.mapIndexed { index, stat ->
            mergeTranslatedStats(
                original = stat,
                translated = translated.mapNotNull { it.stats.getOrNull(index) },
                reminderTexts = reminderTexts
            )
        }
        return AptDataRepo.StatGroup(translatedStats, original.rawData.deepCopy()).also {
            it.syncToRawData()
        }
    }

    internal fun mergeMapperTranslations(
        original: AptDataRepo.BaseStat,
        translated: List<AptDataRepo.BaseStat>,
        reminderTexts: Sequence<Map<String, String>>,
    ): AptDataRepo.BaseStat {
        return when (original) {
            is AptDataRepo.Stat -> mergeTranslatedStats(
                original,
                translated.filterIsInstance<AptDataRepo.Stat>(),
                reminderTexts
            )
            is AptDataRepo.StatGroup -> mergeTranslatedStats(
                original,
                translated.filterIsInstance<AptDataRepo.StatGroup>(),
                reminderTexts
            )
        }
    }

    private fun createTrivialMergeGroup(stats: List<AptDataRepo.Stat>): AptDataRepo.StatGroup {
        val rawData = JsonObject().apply {
            add("resolve", JsonObject().apply {
                addProperty("strat", "trivial-merge")
            })
            add("stats", JsonArray().apply {
                stats.forEach { add(it.rawData) }
            })
        }
        return AptDataRepo.StatGroup(stats, rawData)
    }

    /**
     * Rebuild groups that are lost when distinct English stats receive the same
     * localized matcher. Rules are keyed by English refs so this remains stable
     * across target-language changes.
     */
    internal fun mergeTrivialGroups(
        stats: List<AptDataRepo.BaseStat>
    ): List<AptDataRepo.BaseStat> {
        var result = stats

        for (rule in trivialMergeRules) {
            val matchingIndexes = result.withIndex()
                .filter { (_, stat) ->
                    stat is AptDataRepo.Stat && stat.refName in rule
                }
                .map { it.index }

            if (matchingIndexes.size != rule.size) continue

            val firstIndex = matchingIndexes.min()
            val mergedStats = matchingIndexes
                .sorted()
                .map { result[it] as AptDataRepo.Stat }
            val mergedGroup = createTrivialMergeGroup(mergedStats)

            result = result
                .filterIndexed { index, _ -> index !in matchingIndexes }
                .toMutableList()
                .apply { add(firstIndex, mergedGroup) }
        }

        return result
    }

    private data class RankedMatcher(val mapperIndex: Int, val data: JsonObject)

    private fun translateStat(
        stat: AptDataRepo.Stat,
        mappers: List<GameDataRepo.GameDataMapper>,
        reminderTexts: Sequence<Map<String, String>>,
        location: StatTranslationAudit.Location,
        audit: StatTranslationAudit?,
    ): AptDataRepo.Stat {
        val ids = stat.tradeIds
        val special = stat.rawData.has("mercenary") || stat.rawData.getAsJsonObject("trade")?.get("option")?.asBoolean == true
        val hashes = if (special) emptyList() else TradeStatResolver.hashes(ids)
        val candidates = mappers.flatMap { mapper -> hashes.flatMap(mapper::tradeCandidates) }
        val configuredCsdAliases = ids.flatMap { tradeCsdAliases[it].orEmpty() }
        val aliasCandidates = mutableMapOf<Long, List<TradeCsdCandidate>>()

        data class Outcome(
            val resolution: TradeStatResolver.Resolution,
            val legacy: List<RankedMatcher>,
            val localized: List<RankedMatcher>,
            val refScopedAliases: List<JsonObject>,
        )
        val outcomes = stat.matchers.map { matcher ->
            val resolution = TradeStatResolver.resolve(matcher, hashes, candidates, ::specialFix, audit != null)
            val legacy = if (audit != null || resolution.accepted.isEmpty()) {
                mappers.flatMapIndexed { index, mapper ->
                    stat.translateLegacy(matcher, mapper).map { RankedMatcher(index, it) }
                }
            } else emptyList()
            // Manual aliases scoped by APT ref cover older client variants. Don't reuse
            // entries found only by the global English matcher text on the ID path.
            val aliases = if (resolution.accepted.isEmpty()) emptyList() else mappers.flatMapIndexed { index, mapper ->
                refScopedAliases(mapper.secondaryStats[stat.refName.uppercase()].orEmpty(), matcher)
                    .map { RankedMatcher(index, it) }
            }
            val csdAliases = if (resolution.accepted.isEmpty()) emptyList() else TradeStatResolver.resolveAliases(
                matcher, ids, configuredCsdAliases,
                { hash -> aliasCandidates.getOrPut(hash) { mappers.flatMap { it.tradeCandidates(hash) } } },
                ::specialFix,
            ).map { match ->
                RankedMatcher(
                    mappers.indexOfFirst { it.id == match.origins.first().mapper },
                    TradeStatResolver.apply(matcher, match.translation),
                )
            }
            val localized = when {
                resolution.accepted.isNotEmpty() -> resolution.accepted.map { match ->
                    RankedMatcher(
                        mappers.indexOfFirst { it.id == match.origins.first().mapper },
                        TradeStatResolver.apply(matcher, match.translation),
                    )
                } + aliases + csdAliases
                // Don't choose an ambiguous rule or add global text matches from other stats.
                // Retain previously shipped variants only when that same mapper verifies them
                // against the same internal stat sequence.
                resolution.status == "conflict" -> retainVerifiedLegacy(matcher, resolution, legacy, mappers)
                else -> legacy
            }
            Outcome(resolution, legacy, localized, (aliases + csdAliases).map { it.data }.distinct())
        }

        val someLegacy = outcomes.any { it.legacy.isNotEmpty() }
        val someLocalized = outcomes.any { it.localized.isNotEmpty() }
        val output = stat.matchers.zip(outcomes).flatMapIndexed { index, (matcher, outcome) ->
            // Preserve the unresolved English matcher on a conflict rather than silently dropping it.
            val emitted = outcome.localized.ifEmpty {
                if (!someLocalized || outcome.resolution.status == "conflict") {
                    listOf(RankedMatcher(mappers.size, matcher.rawData))
                } else emptyList()
            }.sortedBy { it.mapperIndex }
            audit?.matcher(
                location, index, stat.refName, ids, matcher.rawData, outcome.resolution,
                legacy = outcome.legacy.map { it.data }.ifEmpty { if (someLegacy) emptyList() else listOf(matcher.rawData) },
                output = emitted.map { it.data },
                refScopedAliases = outcome.refScopedAliases,
                legacyMappers = outcome.legacy.map { mappers[it.mapperIndex].id }.distinct(),
                legacyCsdOrigins = if (stat.rawData.has("mercenary")) emptyList() else outcome.legacy.flatMap { ranked ->
                    val mapper = mappers[ranked.mapperIndex]
                    fun sources(field: String, source: String?) = source?.let {
                        mapper.legacyCsdOrigins(it).mapNotNull { (text, origin) ->
                            if (specialFix(text) == ranked.data[field]?.asString) {
                                StatTranslationAudit.LegacyOrigin(field, specialFix(text), origin)
                            } else null
                        }
                    }.orEmpty()
                    sources("string", matcher.string) + sources("advanced", matcher.advanced)
                }.distinct(),
            )
            emitted
        }.sortedBy { it.mapperIndex }.map { it.data }.distinct()

        val result = mergeTranslatedStats(stat, emptyList(), reminderTexts)
        result.replaceMatchers(output)
        audit?.stat(stat.rawData, result.rawData)
        return result
    }

    private fun retainVerifiedLegacy(
        matcher: AptDataRepo.Stat.Matcher,
        resolution: TradeStatResolver.Resolution,
        legacy: List<RankedMatcher>,
        mappers: List<GameDataRepo.GameDataMapper>,
    ): List<RankedMatcher> {
        if (resolution.matches.flatMap { it.origins }.map { it.statIds }.distinct().size != 1) return emptyList()
        val verified = resolution.matches.flatMap { match ->
            val data = TradeStatResolver.apply(matcher, match.translation)
            match.origins.map { it.mapper to data }
        }.toSet()
        return legacy.filter { (mappers[it.mapperIndex].id to it.data) in verified }
    }

    private fun generate(
        mappers: List<GameDataRepo.GameDataMapper>,
        audit: StatTranslationAudit? = null,
    ): List<AptDataRepo.BaseStat> {
        val reminderTexts = mappers.asSequence().map { it.reminderTexts }
        val merged = AptDataRepo.enStatOrGroup.mapIndexed { index, statOrGroup ->
            when (statOrGroup) {
                is AptDataRepo.Stat -> translateStat(
                    statOrGroup, mappers, reminderTexts, StatTranslationAudit.Location(index + 1), audit,
                )
                is AptDataRepo.StatGroup -> AptDataRepo.StatGroup(
                    statOrGroup.stats.mapIndexed { childIndex, stat ->
                        translateStat(stat, mappers, reminderTexts, StatTranslationAudit.Location(index + 1, childIndex), audit)
                    },
                    statOrGroup.rawData.deepCopy(),
                ).also { it.syncToRawData() }
            }
        }
        return mergeTrivialGroups(merged).distinctBy { it.rawData.toString() }
    }

    fun patch(mappers: List<GameDataRepo.GameDataMapper>) {
        val normalized = generate(mappers)
        outputFile.bufferedWriter().use { writer ->
            normalized.forEach { writer.appendLine(it.rawData.toString()) }
        }
    }

    fun audit(mappers: List<GameDataRepo.GameDataMapper>) {
        System.out.writer(Charsets.UTF_8).buffered().use { writer ->
            val audit = StatTranslationAudit(writer, mappers)
            val output = generate(mappers, audit)
            audit.finish(output.size)
        }
    }
}
