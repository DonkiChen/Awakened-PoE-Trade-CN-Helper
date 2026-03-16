package stat

import data.AptDataRepo
import data.GameDataRepo
import data.parser.ExtraStat
import java.io.File


object StatPatcher {
    private val outputFile = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data/zh_CN/stats.ndjson")

    private fun AptDataRepo.StatGroup.translateStringAndAdvanced(mapper: GameDataRepo.GameDataMapper): AptDataRepo.StatGroup {
        stats.map { it.translateStringAndAdvanced(mapper) }
        syncToRawData()
        return this
    }

    private fun specialFix(text: String): String {
        // 有些词缀在换行前会有空格, 例如: 以阿华纳（阿华纳 - 夏巴夸亚）的名义用 # 名祭品之血浸染 \n范围内的天赋被瓦尔抑制
        // 这里处理一下
        return text.replace(Regex(" +\n"), "\n")
    }

    private fun translateByStatsFromDescriptions(
        mapper: GameDataRepo.GameDataMapper,
        stat: AptDataRepo.Stat,
        matcher: AptDataRepo.Stat.Matcher,
    ): Boolean {
        val cnStatNames = mapper.statsFromDescriptions[matcher.string.uppercase()] ?: emptySet()
        // 确保最少有一个, 保证后续笛卡尔积计算正确
        val cnAdvanceds =
            if (matcher.advanced == null) setOf("") else mapper.statsFromDescriptions[matcher.advanced.uppercase()] ?: setOf("")
        if (cnStatNames.isEmpty() && cnAdvanceds.size == 1 && cnAdvanceds.first().isBlank()) {
            return false
        }

        val combinations = cnStatNames.flatMap { cnStatName ->
            cnAdvanceds.map { cnAdvanced -> cnStatName to cnAdvanced }
        }

        val backupMatcherRawData = matcher.rawData
        // 因为会出现同一个英文名在不同场景下有不同中文翻译的问题, 例如:
        // Adds {0} to {1} Cold Damage 可以被翻译为
        // - 附加 {0} - {1} 基础冰霜伤害 与
        // - 该装备附加 {0} - {1} 基础冰霜伤害
        // 这里的处理方式是:
        // - 如果只有一条中文翻译: 没问题, 直接修改
        // - 如果有多条: 则直接添加 matcher
        combinations.forEachIndexed { index, (cnStatName, cnAdvanced) ->
            if (index == 0) {
                matcher.updateString(specialFix(cnStatName))
                matcher.updateAdvancedIfExists(specialFix(cnAdvanced))
            } else {
                val copied = matcher.copy(rawData = backupMatcherRawData.deepCopy())
                copied.updateString(specialFix(cnStatName))
                matcher.updateAdvancedIfExists(specialFix(cnAdvanced))
                stat.addMatcher(copied)
            }
        }

        return true
    }

    private fun AptDataRepo.Stat.translateStringAndAdvanced(mapper: GameDataRepo.GameDataMapper): AptDataRepo.Stat {
        matchers.forEach { matcher ->
            if (refName == "Socketed Gems are Supported by Level # Impending Doom") {
                println()
            }
            // 优先从游戏 description 数据里拿
            if (translateByStatsFromDescriptions(mapper, this, matcher)
                || matcher.replaceByExtraStat(mapper.extraStats[refName.uppercase()])
                || matcher.replaceByExtraStat(mapper.extraStats[matcher.string.uppercase()])
            ) {
                return@forEach
            }
            println("missing: $matcher at ${this.refName}")
        }
        return this
    }

    private fun AptDataRepo.Stat.Matcher.replaceByExtraStat(extraStat: ExtraStat?): Boolean {
        extraStat ?: return false
        val index = extraStat.en.indexOfFirst { it.string.equals(string, true) }
        if (index >= 0 && extraStat.cn.size > index) {
            updateString(extraStat.cn[index].string)
            updateAdvancedIfExists(extraStat.cn[index].advanced)
            return true
        }
        return false
    }

    private fun AptDataRepo.BaseStat.translate(mapper: GameDataRepo.GameDataMapper): AptDataRepo.BaseStat {
        return when (this) {
            is AptDataRepo.Stat -> deepClone().translateStringAndAdvanced(mapper)
            is AptDataRepo.StatGroup -> deepClone().translateStringAndAdvanced(mapper)
        }
    }

    fun patch(mappers: List<GameDataRepo.GameDataMapper>) {
        outputFile.bufferedWriter()
            .use { writer ->
                for (statOrGroup in AptDataRepo.enStatOrGroup) {
                    mappers.map { statOrGroup.translate(it) }
                        .distinct()
                        .map { it.rawData.toString() }
                        .forEach(writer::appendLine)
                }
            }
    }
}