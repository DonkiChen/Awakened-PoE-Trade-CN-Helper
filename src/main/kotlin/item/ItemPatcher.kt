package item

import data.AptDataRepo
import data.GameDataRepo
import java.io.File

object ItemPatcher {
    private val outputFile = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data/zh_CN/items.ndjson")

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
        return copy(rawData = rawData.deepCopy()).updateName(cnName ?: name)
    }

    private fun AptDataRepo.Item.translateScryingOrbArea(
        mapAreas: Map<String, String>
    ): AptDataRepo.Item {
        if (rawData["tradeDisc"]?.asString != "scrying_orb") {
            return this
        }

        val disc = rawData["disc"]?.asJsonObject ?: return this
        val area = disc["sectionText"]?.asString ?: return this
        val translatedArea = mapAreas[area] ?: return this
        disc.addProperty("sectionText", translatedArea)
        return this
    }

    fun patch(mappers: List<GameDataRepo.GameDataMapper>) {
        val allCandidates =
            mappers.map { mapper -> listOf(mapper.baseItems, mapper.activeSkills, mapper.words, mapper.monsters) }
        outputFile.bufferedWriter()
            .use { writer ->
                for (item in AptDataRepo.enItems) {
                    allCandidates.mapIndexed { index, candidates ->
                        item.translate(candidates)
                            .translateScryingOrbArea(mappers[index].mapAreas)
                    }
                        .distinct()
                        .map { it.rawData.toString() }
                        .forEach(writer::appendLine)
                }
            }
    }
}
