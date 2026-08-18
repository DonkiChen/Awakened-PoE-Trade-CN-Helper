package item

import data.AptDataRepo
import data.GameDataRepo
import java.io.File

object ItemPatcher {
    private val outputFile = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data/zh_CN/items.ndjson")
    private val extraAreaNames = mapOf(
        "Kishara's Rest" to "琪莎拉之息",
        "Unremarkable Seabed" to "平凡海床",
    )

    private fun AptDataRepo.Item.choose(candidates: List<Map<String, String>>): String? {
        candidates.forEach {
            val cnName = it[refName]
            if (cnName != null) {
                return cnName
            }
        }
        return null
    }

    private fun AptDataRepo.Item.translate(candidates: List<Map<String, String>>): AptDataRepo.Item {
        val cnName = choose(candidates)
        if (cnName == null) {
            println("Missing $refName")
        }
        val translatedName = cnName ?: name
        val normalizedName = if (rawData["namespace"]?.asString == "AREA") {
            translatedName.trim()
        } else {
            translatedName
        }
        return copy(rawData = rawData.deepCopy()).updateName(normalizedName)
    }

    fun patch(mappers: List<GameDataRepo.GameDataMapper>) {
        outputFile.bufferedWriter()
            .use { writer ->
                for (item in AptDataRepo.enItems) {
                    mappers.map { mapper ->
                        val candidates = if (item.rawData["namespace"]?.asString == "AREA") {
                            listOf(mapper.worldAreas, mapper.achievementItems, extraAreaNames)
                        } else {
                            listOf(mapper.baseItems, mapper.activeSkills, mapper.words, mapper.monsters)
                        }
                        item.translate(candidates)
                    }
                        .distinct()
                        .map { it.rawData.toString() }
                        .forEach(writer::appendLine)
                }
            }
    }
}
