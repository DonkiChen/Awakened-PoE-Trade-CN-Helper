package stat

import data.AptDataRepo
import data.GameDataRepo
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

    private fun doReplace(
        cnMatcherNames: Set<String>,
        cnAdvancedNames: Set<String>,
        stat: AptDataRepo.Stat,
        matcher: AptDataRepo.Stat.Matcher,
    ): Boolean {
        if (cnMatcherNames.isEmpty() && cnAdvancedNames.isEmpty()) {
            return false
        }
        if (matcher.advanced != null && cnAdvancedNames.isEmpty()) {
            // 如果 matcher 有 advanced, 则必须要有 cnAdvancedNames
            return false
        }
        val backupMatcherRawData = matcher.rawData.deepCopy()

        val finalCnAdvancedNames = cnAdvancedNames.ifEmpty {
            // 确保最少有一个, 保证后续笛卡尔积计算正确
            setOf("")
        }
        val combinations = cnMatcherNames.flatMap { cnStatName ->
            finalCnAdvancedNames.map { cnAdvanced -> cnStatName to cnAdvanced }
        }

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
                matcher.updateAdvancedIfExists(specialFix(cnAdvanced))
                stat.addMatcher(copied)
            }
        }

        return true
    }

    private fun AptDataRepo.Stat.translateStringAndAdvanced(mapper: GameDataRepo.GameDataMapper): AptDataRepo.Stat {
        matchers.forEach { matcher ->
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

            doReplace(
                cnMatcherNames = cnMatcherNames,
                cnAdvancedNames = cnAdvancedNames,
                stat = this,
                matcher = matcher
            )
        }
        return this
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
                    val translatedStatOrStatGroup = mappers
                        .map {
                            statOrGroup.translate(it)
                        }
                        .distinct()
                    if (translatedStatOrStatGroup.all { statOrGroup.rawData == it.rawData }) {
                        println("missing: ${statOrGroup.rawData}")
                    }

                    translatedStatOrStatGroup.forEach { writer.appendLine(it.rawData.toString()) }
                }
            }
    }
}