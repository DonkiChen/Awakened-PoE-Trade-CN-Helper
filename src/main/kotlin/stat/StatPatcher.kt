package stat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import data.AptDataRepo
import data.GameDataRepo
import java.io.File


object StatPatcher {
    private val outputFile = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data/zh_CN/stats.ndjson")

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

    private fun AptDataRepo.StatGroup.translateStringAndAdvanced(mapper: GameDataRepo.GameDataMapper): AptDataRepo.StatGroup {
        stats.forEach { it.translateStringAndAdvanced(mapper) }
        syncToRawData()
        return this
    }

    private fun specialFix(text: String): String {
        // 有些词缀在换行前会有空格, 例如: 以阿华纳（阿华纳 - 夏巴夸亚）的名义用 # 名祭品之血浸染 \n范围内的天赋被瓦尔抑制
        // 这里处理一下
        return text.replace(Regex(" +\n"), "\n")
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

    private fun AptDataRepo.Stat.translateStringAndAdvanced(mapper: GameDataRepo.GameDataMapper): AptDataRepo.Stat {
        val mercenary = rawData["mercenary"]?.asJsonObject
        if (mercenary != null) {
            val translatedName: String
            val tier: Int?
            if (mercenary.has("tier")) {
                val support = mapper.mercenarySupports[refName] ?: return this
                translatedName = support.name
                tier = support.tier
            } else {
                translatedName = mapper.mercenarySkills[refName] ?: return this
                tier = null
            }

            val tierText = if (tier != null) {
                mapper.clientStrings["ModDescriptionLineTier"]
                    ?.replace("{0}", tier.toString())
                    ?: error("Missing ClientString: ModDescriptionLineTier")
            } else {
                ""
            }
            replaceMatchers(matchers.map { matcher ->
                matcher.rawData.deepCopy().also { rawData ->
                    rawData.addProperty("string", translatedName)
                    if (rawData.has("advanced")) {
                        rawData.addProperty("advanced", translatedName + tierText)
                    }
                }
            })
            return this
        }

        val translatedMatchers = matchers.toList().flatMap { matcher ->
            if (refName == "+# to Level of all Raise Spectre Gems") {
                println()
            }
            val cnMatcherNames = mutableSetOf<String>()
            val cnAdvancedNames = mutableSetOf<String>()

            if (mapper.statsFromDescriptions[matcher.string.uppercase()] != null) {
                cnMatcherNames.addAll(mapper.statsFromDescriptions[matcher.string.uppercase()]!!)
            }
            if (matcher.advanced != null && mapper.statsFromDescriptions[matcher.advanced.uppercase()] != null) {
                cnAdvancedNames.addAll(mapper.statsFromDescriptions[matcher.advanced.uppercase()]!!)
            }

            if (matcher.advanced != null && cnAdvancedNames.isEmpty()) {
                // 如果 matcher 有 advanced, 则必须要有 cnAdvancedNames
                cnMatcherNames.clear()
            }

            val extraStats = buildList {
                if (mapper.extraStats[refName.uppercase()] != null) {
                    add(mapper.extraStats[refName.uppercase()]!!)
                }
                if (mapper.extraStats[matcher.string.uppercase()] != null) {
                    add(mapper.extraStats[matcher.string.uppercase()]!!)
                }
            }
            extraStats.forEach { extraStat ->
                val index = extraStat.en.indexOfFirst { it.string.equals(matcher.string, true) }
                if (index >= 0 && extraStat.cn.size > index) {
                    cnMatcherNames.add(extraStat.cn[index].string)
                    if (extraStat.cn[index].advanced != null) {
                        cnAdvancedNames.add(extraStat.cn[index].advanced!!)
                    }
                }
            }

            createCombinations(cnMatcherNames, cnAdvancedNames, matcher).map { (cnStatName, cnAdvanced) ->
                matcher.rawData.deepCopy().also { rawData ->
                    rawData.addProperty("string", specialFix(cnStatName))
                    if (rawData.has("advanced") && cnAdvanced.isNotBlank()) {
                        rawData.addProperty("advanced", specialFix(cnAdvanced))
                    }
                }
            }
        }
        replaceMatchers(translatedMatchers.distinctBy { it.toString() })
        return this
    }

    private fun AptDataRepo.BaseStat.translate(mapper: GameDataRepo.GameDataMapper): AptDataRepo.BaseStat {
        return when (this) {
            is AptDataRepo.Stat -> deepClone().translateStringAndAdvanced(mapper)
            is AptDataRepo.StatGroup -> deepClone().translateStringAndAdvanced(mapper)
        }
    }

    private fun mergeTranslatedStats(
        original: AptDataRepo.Stat,
        translated: List<AptDataRepo.Stat>,
    ): AptDataRepo.Stat {
        val translatedMatchers = translated
            .filter { it.hasMatchers() }
            .flatMap { stat -> stat.matchers.map { it.rawData.deepCopy() } }
            .distinctBy { it.toString() }

        return original.deepClone().also { result ->
            if (translatedMatchers.isNotEmpty()) {
                result.replaceMatchers(translatedMatchers)
            }
        }
    }

    private fun mergeTranslatedStats(
        original: AptDataRepo.StatGroup,
        translated: List<AptDataRepo.StatGroup>,
    ): AptDataRepo.StatGroup {
        val translatedStats = original.stats.mapIndexed { index, stat ->
            mergeTranslatedStats(
                original = stat,
                translated = translated.mapNotNull { it.stats.getOrNull(index) }
            )
        }
        return AptDataRepo.StatGroup(translatedStats, original.rawData.deepCopy()).also {
            it.syncToRawData()
        }
    }

    private fun mergeMapperTranslations(
        original: AptDataRepo.BaseStat,
        translated: List<AptDataRepo.BaseStat>,
    ): AptDataRepo.BaseStat {
        return when (original) {
            is AptDataRepo.Stat -> mergeTranslatedStats(
                original,
                translated.filterIsInstance<AptDataRepo.Stat>()
            )
            is AptDataRepo.StatGroup -> mergeTranslatedStats(
                original,
                translated.filterIsInstance<AptDataRepo.StatGroup>()
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

    fun patch(mappers: List<GameDataRepo.GameDataMapper>) {
        val translatedByMapper = mappers.map { mapper ->
            AptDataRepo.enStatOrGroup.map { statOrGroup ->
                statOrGroup.translate(mapper)
            }
        }

        val merged = AptDataRepo.enStatOrGroup.mapIndexed { index, statOrGroup ->
            mergeMapperTranslations(
                original = statOrGroup,
                translated = translatedByMapper.map { it[index] }
            )
        }
        val normalized = mergeTrivialGroups(merged)
            .distinctBy { it.rawData.toString() }

        outputFile.bufferedWriter().use { writer ->
            normalized.forEach { writer.appendLine(it.rawData.toString()) }
        }
    }
}
