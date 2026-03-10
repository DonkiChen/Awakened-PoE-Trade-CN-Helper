package stat

import data.AptDataRepo
import data.GameDataRepo
import data.parser.ExtraStat
import java.io.File


object StatPatcher {
    private val outputFile = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data/zh_CN/stats.ndjson")

    private fun AptDataRepo.StatGroup.translateStringAndAdvanced(mapper: GameDataRepo.GameDataMapper): AptDataRepo.StatGroup {
        stats.forEach { it.translateStringAndAdvanced(mapper) }
        return this
    }

    private fun AptDataRepo.Stat.translateStringAndAdvanced(mapper: GameDataRepo.GameDataMapper): AptDataRepo.Stat {
        matchers.forEach { matcher ->
            // 优先从游戏 description 数据里拿
            val cnStatName = mapper.statsFromDescriptions[matcher.string.uppercase()]
            if (cnStatName != null) {
                matcher.updateString(cnStatName)
                return@forEach
            }

            if (matcher.replaceByExtraStat(mapper.extraStats[refName.uppercase()])
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
            is AptDataRepo.Stat -> copy(rawData = rawData.deepCopy()).translateStringAndAdvanced(mapper)
            is AptDataRepo.StatGroup -> copy(rawData = rawData.deepCopy()).translateStringAndAdvanced(mapper)
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