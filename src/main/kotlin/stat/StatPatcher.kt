package stat

import data.AptDataRepo
import data.GameDataRepo
import data.parser.ExtraStat
import java.io.File


object StatPatcher {
    private val outputFile = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data/zh_CN/stats.ndjson")

    private fun AptDataRepo.Stat.replaceStringAndAdvanced() {
        matchers.forEach { matcher ->
            // 优先从游戏 description 数据里拿
            val cnStatName = GameDataRepo.statsFromDescriptions[matcher.string.uppercase()]
            if (cnStatName != null) {
                matcher.updateString(cnStatName)
                return@forEach
            }

            if (matcher.replaceByExtraStat(GameDataRepo.extraStats[refName.uppercase()])
                || matcher.replaceByExtraStat(GameDataRepo.extraStats[matcher.string.uppercase()])
            ) {
                return@forEach
            }
            println("missing: $matcher at ${this.refName}")
        }
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

    fun patch() {
        outputFile.bufferedWriter().use { writer ->
            AptDataRepo.enStatOrGroup.forEach statOrGroup@{ statOrGroup ->
                when (statOrGroup) {
                    is AptDataRepo.Stat -> statOrGroup.replaceStringAndAdvanced()
                    is AptDataRepo.StatGroup -> statOrGroup.stats.forEach { it.replaceStringAndAdvanced() }
                }
                writer.appendLine(statOrGroup.rawData.toString())
            }
        }
    }
}