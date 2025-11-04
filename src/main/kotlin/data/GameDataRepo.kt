package data

import Config
import data.parser.BaseTableItem
import data.parser.parseExtraStats
import data.parser.parseStatDescriptions
import util.fromJson
import java.io.File
import kotlin.math.min

object GameDataRepo {
    private val dataRepoDir = File("data_repo")
    private val exportedDataDir = File(dataRepoDir, "exported")
    private val extraStatsDir = File(dataRepoDir, "extra")
    private val extraStatsFile = File(extraStatsDir, "extra_stats.json")

    private inline fun <reified T : BaseTableItem> parseExportedTableData(
        gameFileName: String,
        predicate: (T) -> Boolean = { true }
    ): Map<String, String> = data.parser.parseExportedTableData<T>(exportedDataDir, gameFileName, predicate)

    val activeSkills by lazy {
        parseExportedTableData<BaseTableItem>("ActiveSkills.json") {
            // Royale和普通技能英文名一样, 但中文名不一样
            !it.id.endsWith("Royale")
        }
    }

    val monsters by lazy {
        parseExportedTableData<BaseTableItem>("MonsterVarieties.json") {
            // 有些怪物在中文中的灵体名和野兽名不一样...
            !it.id.endsWith("Spectre")
        }
    }

    val passiveSkills by lazy {
        parseExportedTableData<BaseTableItem>("PassiveSkills.json")
    }

    val expeditionAreas by lazy {
        parseExportedTableData<BaseTableItem>("WorldAreas.json") {
            it.id.startsWith("Expedition")
        }
    }

    val kalandraTiles by lazy {
        val file = File(extraStatsDir, "kalandra_tiles.json")
        fromJson<List<String>>(file.reader()).associateBy { it }
    }

    val betrayalNpcs by lazy {
        parseExportedTableData<BaseTableItem>("NPCs.json") {
            it.id.startsWith("Metadata/Monsters/LeagueBetrayal/Betrayal")
        }
    }

    val ascendancies by lazy {
        buildMap {
            putAll(parseExportedTableData<BaseTableItem>("Ascendancy.json"))
            putAll(parseExportedTableData<BaseTableItem>("PassiveSkills.json") { it.id.startsWith("Ascendancy") })
        }
    }

    val keystones by lazy {
        buildMap {
            putAll(rawStatsFromStatDescriptions
                .filter { it.id.startsWith("keystone_") }
                .flatMap { stat ->
                    stat.enNames.mapIndexed { index, enName ->
                        enName to stat.cnNames[index]
                    }
                }.toMap()
            )
            putAll(parseExportedTableData<BaseTableItem>("AchievementItems.json") { it.id.startsWith("Keystone") })
            putAll(parseExportedTableData<BaseTableItem>("PassiveSkills.json") { it.id.contains("keystone") })
        }
    }

    // 偷懒直接用全量数据了
    val exarchEaterMods by lazy { statsFromDescriptions }

    val words by lazy {
        parseExportedTableData<BaseTableItem>("Words.json")
    }

    val baseItems by lazy {
        parseExportedTableData<BaseTableItem>("BaseItemTypes.json") {
            !it.id.contains("Royale")
                    // 电能释放 被覆盖了
                    && it.id != "Metadata/Items/Gems/SkillGemLightningTendrilsChannelled"
        }
    }

    /**
     * 手动维护的数据
     */
    val extraStats by lazy {
        parseExtraStats(extraStatsFile).associateBy { it.refName.uppercase() }
    }

    private val rawStatsFromStatDescriptions by lazy {
        val statDescriptionDirName = if (Config.usePatchedIntlStatDescriptionFiles) "intl" else "tencent"
        parseStatDescriptions(File(exportedDataDir, "${statDescriptionDirName}/files"))
    }

    val statsFromDescriptions: Map<String, String> by lazy {
        rawStatsFromStatDescriptions
            .asSequence()
            .filter {
                // fishing_lure_type 英文名有 6 个, 中文名只有 4 个, 直接跳过
                it.id != "fishing_lure_type"
            }
            .filter {
                // println("Stat ${stat.id} has no cnNames")
                it.cnNames.isNotEmpty()
            }
            .flatMap { stat ->
                stat.enNames.mapIndexed { index, enName ->
                    // 如果 cn 不够, 则统一用最后一个兜底
                    enName.uppercase() to stat.cnNames[min(index, stat.cnNames.size - 1)]
                }
            }.toMap()
    }
}