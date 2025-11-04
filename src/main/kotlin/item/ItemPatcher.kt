package item

import data.AptDataRepo
import data.GameDataRepo
import java.io.File

object ItemPatcher {
    private val outputFile = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data/zh_CN/items.ndjson")
    private val candidates =
        listOf(GameDataRepo.baseItems, GameDataRepo.activeSkills, GameDataRepo.words, GameDataRepo.monsters)

    private fun AptDataRepo.Item.choose(): String? {
        candidates.forEach {
            val cnName = it[refName]
            if (cnName != null) {
                return cnName
            }
        }
        return null
    }

    fun patch() {
        outputFile.bufferedWriter().use { writer ->
            AptDataRepo.enItems.forEach { item ->
                val cnName = item.choose()
                if (cnName == null) {
                    println("Missing ${item.refName}")
                }
                item.updateName(cnName ?: item.name)
                writer.appendLine(item.rawData.toString())
            }
        }
    }
}