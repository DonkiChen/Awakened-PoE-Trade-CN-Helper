package data.parser

import com.google.gson.annotations.SerializedName
import java.io.File

internal fun clusterJewelStats(
    sourceTables: File,
    targetTables: File,
    source: List<GameStatDescription>,
    target: List<GameStatDescription>,
    sourceLanguage: String,
    targetLanguage: String,
    sourceTemplate: String,
    targetTemplate: String,
    report: (String) -> Unit,
): List<Pair<String, String>> {
    val expansionSkills: List<ExpansionSkill>
    val passives: Map<Int, ExpansionPassive>
    val statIds: Map<Int, String>
    try {
        expansionSkills = sourceTables.readRows<ExpansionSkill>("PassiveTreeExpansionSkills")
        passives = sourceTables.readRows<ExpansionPassive>("PassiveSkills").associateBy { it.row }
        statIds = sourceTables.readRows<ExpansionStat>("Stats").associate { it.row to it.id }
    } catch (e: Exception) {
        report("cluster jewels: ${e.message}")
        return emptyList()
    }

    fun descriptionsByFile(descriptions: List<GameStatDescription>) = descriptions.asSequence()
        .filter { it.statIds.size == 1 }
        .groupBy { it.id to it.fileName.substringAfterLast('@') }

    val sourceDescriptions = descriptionsByFile(source)
    val targetDescriptions = descriptionsByFile(target)
    val sourceEngine = gameCsdEngine(sourceTables, report)
    val targetEngine = gameCsdEngine(targetTables, report)
    val files = listOf("passive_skill_stat_descriptions", "stat_descriptions")
    fun CsdRuleEngine.render(description: GameStatDescription, language: String, value: Int): String? =
        description.rulesByLang[language]?.firstNotNullOfOrNull { rule ->
            if (rule.restrictions.isEmpty()) displayText(rule, listOf(value)) else null
        }

    return expansionSkills.mapNotNull { skill ->
        val passive = passives[skill.passive] ?: return@mapNotNull null
        val keys = passive.stats
        val values = listOf(passive.value1, passive.value2, passive.value3, passive.value4, passive.value5)
        if (keys.isNullOrEmpty() || keys.size > values.size) return@mapNotNull null
        val lines = keys.mapIndexedNotNull { index, key ->
            val id = statIds[key] ?: return@mapIndexedNotNull null
            val value = values[index] ?: return@mapIndexedNotNull null
            files.firstNotNullOfOrNull { file ->
                val from = sourceDescriptions[id to file]?.singleOrNull() ?: return@firstNotNullOfOrNull null
                val to = targetDescriptions[id to file]?.singleOrNull() ?: return@firstNotNullOfOrNull null
                val en = sourceEngine.render(from, sourceLanguage, value) ?: return@firstNotNullOfOrNull null
                val localized = targetEngine.render(to, targetLanguage, value) ?: return@firstNotNullOfOrNull null
                // Multi-line trade matchers follow the order in the main descriptions file.
                val order = sourceDescriptions[id to "stat_descriptions"]?.singleOrNull()?.location?.line ?: from.location.line
                Triple(order, sourceTemplate.replace("{0}", en), targetTemplate.replace("{0}", localized))
            }
        }
        if (lines.size != keys.size) {
            report("cluster jewels: incomplete stat descriptions for passive row ${skill.passive} in $sourceTables")
            return@mapNotNull null
        }
        val sorted = lines.sortedBy { it.first }
        sorted.joinToString("\n") { it.second } to sorted.joinToString("\n") { it.third }
    }
}

private data class ExpansionSkill(@SerializedName("PassiveSkillsKey") val passive: Int)

private data class ExpansionPassive(
    @SerializedName("_index") val row: Int,
    @SerializedName("Stats") val stats: List<Int>?,
    @SerializedName("Stat1Value") val value1: Int?,
    @SerializedName("Stat2Value") val value2: Int?,
    @SerializedName("Stat3Value") val value3: Int?,
    @SerializedName("Stat4Value") val value4: Int?,
    @SerializedName("Stat5Value") val value5: Int?,
)

private data class ExpansionStat(
    @SerializedName("_index") val row: Int,
    @SerializedName("Id") val id: String,
)
