package data

import data.parser.BaseTableItem
import data.parser.ClientString
import data.parser.MercenarySupport
import data.parser.StatDescriptionParsers
import data.parser.parseExtraStats
import java.io.File
import kotlin.math.min


private val dataRepoDir = File("data_repo")
private val exportedDataDir = File(dataRepoDir, "exported")
private val extraStatsDir = File(dataRepoDir, "extra")
private val extraStatsFile = File(extraStatsDir, "extra_stats.json")

object GameDataRepo {
    private val _mappers = mutableListOf<GameDataMapper>()
    val mappers: List<GameDataMapper> = _mappers

    /**
     * 注册一组源数据和目标数据。多个 mapper 彼此独立，因为不同目标导出可能使用不同的语言 key。
     *
     * @param sourceExportDirName `data_repo/exported` 下提供源 stat 描述的数据目录名，通常包含英文文案
     * @param targetExportDirName `data_repo/exported` 下提供目标 table 和 stat 描述的数据目录名
     * @param targetLanguageKey 目标导出中使用的语言 key。例如国际服将简体中文文案标记为 "Traditional Chinese"
     * @param sourceStatUnlabelledLanguage 源 stat 描述中没有 `lang` 标记的内容所属语言，通常为 "English"
     * @param targetStatUnlabelledLanguage 目标 stat 描述中没有 `lang` 标记的内容所属语言，可根据目标导出分别配置
     */
    fun prepareMapper(
        sourceExportDirName: String,
        targetExportDirName: String,
        targetLanguageKey: String,
        sourceStatUnlabelledLanguage: String,
        targetStatUnlabelledLanguage: String,
    ) {
        _mappers.add(
            GameDataMapper(
                sourceExportDirName,
                targetExportDirName,
                targetLanguageKey,
                sourceStatUnlabelledLanguage,
                targetStatUnlabelledLanguage,
            )
        )
    }

    class GameDataMapper(
        private val sourceExportDirName: String,
        private val targetExportDirName: String,
        private val targetLanguageKey: String,
        private val sourceStatUnlabelledLanguage: String,
        private val targetStatUnlabelledLanguage: String,
    ) {

        private inline fun <reified T : BaseTableItem> parseTableDataToMapper(
            gameFileName: String,
            predicate: (T) -> Boolean = { true }
        ): Map<T, T> = data.parser.parseExportedTableDataToMapper<T>(
            exportedDataDir = exportedDataDir,
            gameBaseDir = targetExportDirName,
            gameFileName = gameFileName,
            cnLang = targetLanguageKey,
            predicate = predicate
        )

        /**
         * @return key: item 英文名称, value: item 中文名称
         */
        private inline fun <reified T : BaseTableItem> parseTableDataToTextMapper(
            gameFileName: String,
            predicate: (T) -> Boolean = { true }
        ): Map<String, String> {
            return parseTableDataToMapper(gameFileName, predicate)
                .map { it.key.name to it.value.name }
                .toMap()
        }

        val activeSkills by lazy {
            parseTableDataToTextMapper<BaseTableItem>("ActiveSkills.json") {
                // Royale和普通技能英文名一样, 但中文名不一样
                !it.id.endsWith("Royale")
            }
        }

        val supportGems by lazy {
            parseTableDataToTextMapper<BaseTableItem>("BaseItemTypes.json") {
                it.id.startsWith("Metadata/Items/Gems/SupportGem")
            }
        }

        val indexableSupportGems by lazy {
            parseTableDataToTextMapper<BaseTableItem>("IndexableSupportGems.json")
        }

        val sortedRawIndexableSupportGems by lazy {
            parseTableDataToMapper<BaseTableItem>("IndexableSupportGems.json")
                .toSortedMap { o1, o2 ->
                    o1.id.toInt().compareTo(o2.id.toInt())
                }.toList()
        }

        val monsters by lazy {
            parseTableDataToTextMapper<BaseTableItem>("MonsterVarieties.json") {
                // 有些怪物在中文中的灵体名和野兽名不一样...
                !it.id.endsWith("Spectre")
            }
        }

        val passiveSkills by lazy {
            parseTableDataToTextMapper<BaseTableItem>("PassiveSkills.json")
        }

        val expeditionAreas by lazy {
            parseTableDataToTextMapper<BaseTableItem>("WorldAreas.json") {
                it.id.startsWith("Expedition")
            }
        }

        val worldAreas by lazy {
            parseTableDataToTextMapper<BaseTableItem>("WorldAreas.json")
        }

        val achievementItems by lazy {
            parseTableDataToTextMapper<BaseTableItem>("AchievementItems.json")
        }

        val lakeRooms by lazy {
            parseTableDataToTextMapper<BaseTableItem>("LakeRooms.json")
        }

        val incursionRooms by lazy {
            parseTableDataToTextMapper<BaseTableItem>("IncursionRooms.json")
        }

        val logbookFactions by lazy {
            parseTableDataToTextMapper<BaseTableItem>("ExpeditionFactions.json")
        }

        val clientStrings by lazy {
            parseTableDataToMapper<ClientString>("ClientStrings.json")
                .map { (source, target) -> source.id to target.text }
                .toMap()
        }

        val mercenarySkills by lazy {
            parseTableDataToTextMapper<BaseTableItem>("MercenarySkills.json")
        }

        val mercenarySupports by lazy {
            parseTableDataToMapper<MercenarySupport>("MercenarySupports.json")
                .map { (source, target) ->
                    source.name to MercenarySupportTranslation(target.name, target.tier)
                }
                .toMap()
        }

        val betrayalNpcs by lazy {
            parseTableDataToTextMapper<BaseTableItem>("NPCs.json") {
                it.id.startsWith("Metadata/Monsters/LeagueBetrayal/Betrayal")
            }
        }

        val ascendancies by lazy {
            buildMap {
                putAll(parseTableDataToTextMapper<BaseTableItem>("Ascendancy.json"))
                putAll(parseTableDataToTextMapper<BaseTableItem>("PassiveSkills.json") { it.id.startsWith("Ascendancy") })
            }
        }

        val keystones by lazy {
            buildMap {
                val targetStatsById = rawTargetStatsFromStatDescriptions.associateBy { it.uniqueId }
                putAll(rawSourceStatsFromStatDescriptions
                    .filter { it.id.startsWith("keystone_") }
                    .flatMap { stat ->
                        // Keystone 的英文 key 来自源数据，翻译名称必须从目标数据读取。
                        val targetStat = targetStatsById[stat.uniqueId]
                            ?: error("Missing target stat description: ${stat.uniqueId}")
                        val targetNames = targetStat.namesByLang[targetLanguageKey]
                            ?: error("Missing target language '$targetLanguageKey' at: ${targetStat.uniqueId}")
                        stat.namesByLang[sourceStatUnlabelledLanguage]!!.mapIndexed { index, enName ->
                            enName to targetNames[index]
                        }
                    }
                    .toMap()
                )
                putAll(parseTableDataToTextMapper<BaseTableItem>("AchievementItems.json") { it.id.startsWith("Keystone") })
                putAll(parseTableDataToTextMapper<BaseTableItem>("PassiveSkills.json") { it.id.contains("keystone") })
            }
        }

        // 偷懒直接用全量数据了
        val exarchEaterMods by lazy { statsFromDescriptions }

        val words by lazy {
            parseTableDataToTextMapper<BaseTableItem>("Words.json")
        }

        val baseItems by lazy {
            parseTableDataToTextMapper<BaseTableItem>("BaseItemTypes.json") {
                !it.id.contains("Royale")
                        // 电能释放 被覆盖了
                        && it.id != "Metadata/Items/Gems/SkillGemLightningTendrilsChannelled"
            }
        }

        /**
         * 手动维护的数据
         */
        val extraStats by lazy {
            parseExtraStats(this@GameDataMapper, extraStatsFile).groupBy { it.refName.uppercase() }
        }

        private val rawSourceStatsFromStatDescriptions by lazy {
            val dir = File(exportedDataDir, "${sourceExportDirName}/files")
            // 源文件提供英文 matcher 名称。
            StatDescriptionParsers.parse(dir, sourceStatUnlabelledLanguage)
        }
        private val rawTargetStatsFromStatDescriptions by lazy {
            val dir = File(exportedDataDir, "${targetExportDirName}/files")
            // 有些目标导出文件整体已经是本地化内容，因此没有 lang 标记；
            // 这里需要为每个 mapper 单独指定这类内容的语言。
            StatDescriptionParsers.parse(dir, targetStatUnlabelledLanguage)
        }

        val statsFromDescriptions: Map<String, Set<String>> by lazy {
            val enStatsFromGame = rawSourceStatsFromStatDescriptions
            val cnStatsFromGameById = rawTargetStatsFromStatDescriptions.associateBy { it.uniqueId }
            buildMap<String, MutableSet<String>> statsMap@{
                enStatsFromGame
                    .filter {
                        // fishing_lure_type 英文名有 6 个, 中文名只有 4 个, 直接跳过
                        it.id != "fishing_lure_type"
                    }
                    .forEach { sourceDesc ->
                        // 源和目标可以来自不同导出目录，因此通过文件名和 id 匹配，不能依赖翻译后的文案。
                        val targetDesc = cnStatsFromGameById[sourceDesc.uniqueId]
                        val sourceNames = sourceDesc.namesByLang[sourceStatUnlabelledLanguage]
                        val targetNames = targetDesc?.namesByLang?.get(targetLanguageKey)
                        if (targetDesc == null
                            // 国际服中有些是已过期的, 而国服中没有对应字段, 直接跳过
                            || targetNames.isNullOrEmpty()
                        ) {
                            return@forEach
                        } else {
                            if (sourceNames == null || (sourceNames.size != targetNames.size && targetNames.size > 1)) {
                                println("[WARNING] Building dictionary, source names size: ${sourceNames?.size}, target names size: ${targetNames.size} at: ${sourceDesc.uniqueId}")
                                println(sourceNames)
                                println(targetNames)
                                return@forEach
                            }

                            sourceNames.forEachIndexed { index, enName ->
                                val set = getOrPut(enName.uppercase()) { mutableSetOf() }
                                // 如果 cn 不够, 则统一用最后一个兜底
                                set.add(targetNames[min(index, targetNames.size - 1)])
                            }
                        }
                    }
            }
        }
    }

    data class MercenarySupportTranslation(
        val name: String,
        val tier: Int,
    )
}
