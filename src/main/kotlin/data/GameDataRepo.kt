package data

import data.parser.BaseTableItem
import data.parser.parseExtraStats
import data.parser.parseStatDescriptions
import util.fromJson
import java.io.File
import kotlin.math.min


private val dataRepoDir = File("data_repo")
private val exportedDataDir = File(dataRepoDir, "exported")
private val extraStatsDir = File(dataRepoDir, "extra")
private val extraStatsFile = File(extraStatsDir, "extra_stats.json")

object GameDataRepo {
    private val _mappers = mutableListOf<GameDataMapper>()
    val mappers: List<GameDataMapper> = _mappers

    fun prepareMapper(
        sourceBaseDirName: String,
        targetBaseDir: String,
        targetLang: String,
        targetStatDefaultLang: String,
    ) {
        _mappers.add(GameDataMapper(sourceBaseDirName, targetBaseDir, targetLang, targetStatDefaultLang))
    }

    class GameDataMapper(
        private val sourceBaseDirName: String,
        private val targetBaseDir: String,
        private val targetLang: String,
        private val targetStatDefaultLang: String,
    ) {
        /**
         * @return key: item 英文名称, value: item 中文名称
         */
        private inline fun <reified T : BaseTableItem> parseTableDataToMapper(
            gameFileName: String,
            predicate: (T) -> Boolean = { true }
        ): Map<String, String> = data.parser.parseExportedTableDataToMapper<T>(
            exportedDataDir = exportedDataDir,
            gameBaseDir = targetBaseDir,
            gameFileName = gameFileName,
            cnLang = targetLang,
            predicate = predicate
        )

        val activeSkills by lazy {
            parseTableDataToMapper<BaseTableItem>("ActiveSkills.json") {
                // Royale和普通技能英文名一样, 但中文名不一样
                !it.id.endsWith("Royale")
            }
        }

        val supportGems by lazy {
            parseTableDataToMapper<BaseTableItem>("BaseItemTypes.json") {
                it.id.startsWith("Metadata/Items/Gems/SupportGem")
            }
        }

        val monsters by lazy {
            parseTableDataToMapper<BaseTableItem>("MonsterVarieties.json") {
                // 有些怪物在中文中的灵体名和野兽名不一样...
                !it.id.endsWith("Spectre")
            }
        }

        val passiveSkills by lazy {
            parseTableDataToMapper<BaseTableItem>("PassiveSkills.json")
        }

        val expeditionAreas by lazy {
            parseTableDataToMapper<BaseTableItem>("WorldAreas.json") {
                it.id.startsWith("Expedition")
            }
        }

        val kalandraTiles by lazy {
            val file = File(extraStatsDir, "kalandra_tiles.json")
            fromJson<List<String>>(file.reader()).associateBy { it }
        }

        val betrayalNpcs by lazy {
            parseTableDataToMapper<BaseTableItem>("NPCs.json") {
                it.id.startsWith("Metadata/Monsters/LeagueBetrayal/Betrayal")
            }
        }

        val ascendancies by lazy {
            buildMap {
                putAll(parseTableDataToMapper<BaseTableItem>("Ascendancy.json"))
                putAll(parseTableDataToMapper<BaseTableItem>("PassiveSkills.json") { it.id.startsWith("Ascendancy") })
            }
        }

        val keystones by lazy {
            buildMap {
                putAll(rawSourceStatsFromStatDescriptions
                    .filter { it.id.startsWith("keystone_") }
                    .flatMap { stat ->
                        stat.namesByLang[targetStatDefaultLang]!!.mapIndexed { index, enName ->
                            enName to stat.namesByLang[targetLang]!![index]
                        }
                    }
                    .toMap()
                )
                putAll(parseTableDataToMapper<BaseTableItem>("AchievementItems.json") { it.id.startsWith("Keystone") })
                putAll(parseTableDataToMapper<BaseTableItem>("PassiveSkills.json") { it.id.contains("keystone") })
            }
        }

        // 偷懒直接用全量数据了
        val exarchEaterMods by lazy { statsFromDescriptions }

        val words by lazy {
            parseTableDataToMapper<BaseTableItem>("Words.json")
        }

        val baseItems by lazy {
            parseTableDataToMapper<BaseTableItem>("BaseItemTypes.json") {
                !it.id.contains("Royale")
                        // 电能释放 被覆盖了
                        && it.id != "Metadata/Items/Gems/SkillGemLightningTendrilsChannelled"
            }
        }

        /**
         * 手动维护的数据
         */
        val extraStats by lazy {
            parseExtraStats(this@GameDataMapper, extraStatsFile).associateBy { it.refName.uppercase() }
        }

        private val rawSourceStatsFromStatDescriptions by lazy {
            val dir = File(exportedDataDir, "${sourceBaseDirName}/files")
            parseStatDescriptions(dir)
        }
        private val rawTargetStatsFromStatDescriptions by lazy {
            val dir = File(exportedDataDir, "${targetBaseDir}/files")
            parseStatDescriptions(dir, targetStatDefaultLang)
        }

        val statsFromDescriptions by lazy {
            val enStatsFromGame = rawSourceStatsFromStatDescriptions
            val cnStatsFromGameById = rawTargetStatsFromStatDescriptions.associateBy { it.uniqueId }
            buildMap {
                enStatsFromGame
                    .filter {
                        // fishing_lure_type 英文名有 6 个, 中文名只有 4 个, 直接跳过
                        it.id != "fishing_lure_type"
                    }
                    .forEach { sourceDesc ->
                        val targetDesc = cnStatsFromGameById[sourceDesc.uniqueId]
                        val sourceNames = sourceDesc.namesByLang["English"]
                        val targetNames = targetDesc?.namesByLang?.get(targetLang)
                        if (targetDesc == null
                            // 国际服中有些是已过期的, 而国服中没有对应字段, 直接跳过
                            || targetNames.isNullOrEmpty()
                        ) {
                            return@forEach
                        } else {
                            if (sourceNames == null || (sourceNames.size != targetNames.size && targetNames.size > 1)) {
                                println("[WARNING] source names size: ${sourceNames?.size}, target names size: ${targetNames?.size} at: ${sourceDesc.uniqueId}")
                                println(sourceNames)
                                println(targetNames)
                                return@forEach
                            }
                            putAll(sourceNames.mapIndexed { index, name ->
                                // 如果 cn 不够, 则统一用最后一个兜底
                                name.uppercase() to targetNames[min(index, targetNames.size - 1)]
                            }
                                .toMap())
                        }
                    }
            }
        }
    }
}