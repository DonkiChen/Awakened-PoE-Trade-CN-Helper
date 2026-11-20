package data

import data.parser.*
import util.fromJson
import java.io.File


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
        id: String,
        sourceExportDirName: String,
        targetExportDirName: String,
        targetLanguageKey: String,
        sourceStatUnlabelledLanguage: String,
        targetStatUnlabelledLanguage: String,
    ) {
        _mappers.add(
            GameDataMapper(
                id = id,
                sourceExportDirName = sourceExportDirName,
                targetExportDirName = targetExportDirName,
                targetLanguageKey = targetLanguageKey,
                sourceStatUnlabelledLanguage = sourceStatUnlabelledLanguage,
                targetStatUnlabelledLanguage = targetStatUnlabelledLanguage,
            )
        )
    }

    class GameDataMapper(
        // for debug
        val id: String,
        private val sourceExportDirName: String,
        private val targetExportDirName: String,
        private val targetLanguageKey: String,
        private val sourceStatUnlabelledLanguage: String,
        private val targetStatUnlabelledLanguage: String,
    ) {

        internal fun readModNameTables(): Pair<List<ModNameRow>, List<ModNameRow>> {
            val tables = File(exportedDataDir, "$targetExportDirName/tables")
            fun read(language: String): List<ModNameRow> =
                File(tables, "$language/Mods.json").reader().use { fromJson(it) }
            return read("English") to read(targetLanguageKey)
        }

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
            parseTableDataToTextMapper<IncursionRoom>("IncursionRooms.json")
        }

        private val incursionRoomReminderTexts by lazy {
            parseTableDataToMapper<IncursionRoom>("IncursionRooms.json")
                .filter { !it.key.description.isNullOrBlank() && !it.value.description.isNullOrBlank() }
                .map { it.key.description!! to it.value.description!! }
                .toMap()
        }

        val logbookFactions by lazy {
            parseTableDataToTextMapper<BaseTableItem>("ExpeditionFactions.json")
        }

        // id to pair
        val clientStrings: Map<String, Pair<ClientString, ClientString>> by lazy {
            parseTableDataToMapper<ClientString>("ClientStrings.json")
                .map { it.key.id to (it.key to it.value) }
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

        val mercenaryBuilds by lazy {
            parseTableDataToTextMapper<BaseTableItem>("MercenaryBuilds.json")
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
                val targetStatsById = rawTargetStatsFromStatDescriptions.associateBy { it.dictionaryKey() }
                putAll(rawSourceStatsFromStatDescriptions
                    .filter { it.id.startsWith("keystone_") }
                    .flatMap { stat ->
                        val targetStat = targetStatsById[stat.dictionaryKey()] ?: return@flatMap emptyList()
                        pairStatDescriptions(stat, targetStat)
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

        // 裂界守卫
        val elderGuardians by lazy {
            parseTableDataToTextMapper<BaseTableItem>("WorldAreas.json") {
                it.id.startsWith("MavenElderGuardian")
            }
        }

        // 征服者守卫
        val atlasExiles by lazy {
            parseTableDataToTextMapper<BaseTableItem>("NPCs.json") {
                it.id.startsWith("Metadata/Monsters/AtlasExiles/AtlasExile")
            }
        }

        // Supported by Level 1 {0}
        private val builtinSupportGems by lazy {
            val (enClientString, cnClientString) = clientStrings["ItemDisplayBuiltInSupport"]!!
            indexableSupportGems.map {
                enClientString.text.replace("{0}", it.key) to cnClientString.text.replace("{0}", it.value)
            }.toMap()
        }

        // Socketed Gems are Supported by Level {1} {0}
        private val grantsSupportEffectWithLevel by lazy {
            val (enClientString, cnClientString) = clientStrings["ModGrantsSupportEffectWithLevel"]
                ?: return@lazy emptyMap()
            val enTemplate = enClientString.text.replace("{1}", "#")
            val cnTemplate = cnClientString.text.replace("{1}", "#")
            buildMap {
                supportGems.map {
                    // e.g. explicit.effect_1673391040
                    // Socketed Gems are Supported by Level # Added Chaos Damage Support
                    // ->
                    // 镶嵌的宝石被 # 级 附加混沌伤害（辅） 辅助
                    enTemplate
                        .replace("{0}", it.key) to cnTemplate.replace("{0}", it.value)
                }.toMap(this)
            }
        }

        // Grants Level {1} {0}
        private val grantsEffectWithLevel by lazy {
            val (enClientString, cnClientString) = clientStrings["ModGrantsEffectWithLevel"] ?: return@lazy emptyMap()
            val enTemplate = enClientString.text.replace("{1}", "#")
            val cnTemplate = cnClientString.text.replace("{1}", "#")
            activeSkills.map {
                enTemplate
                    .replace("{0}", it.key) to cnTemplate.replace("{0}", it.value)
            }.toMap()
        }

        /**
         * 手动维护的数据
         */
        private val extraStats by lazy {
            parseExtraStats(this@GameDataMapper, extraStatsFile).groupBy { it.refName.uppercase() }
        }

        private val rawSourceStatsFromStatDescriptions by lazy {
            val dir = File(exportedDataDir, "${sourceExportDirName}/files")
            // 源文件提供英文 matcher 名称。
            StatDescriptionParsers.parse(dir, sourceStatUnlabelledLanguage, setOf(sourceStatUnlabelledLanguage))
        }
        private val rawTargetStatsFromStatDescriptions by lazy {
            val dir = File(exportedDataDir, "${targetExportDirName}/files")
            // 有些目标导出文件整体已经是本地化内容，因此没有 lang 标记；
            // 这里需要为每个 mapper 单独指定这类内容的语言。
            StatDescriptionParsers.parse(dir, targetStatUnlabelledLanguage, setOf(targetLanguageKey))
        }

        private fun GameStatDescription.dictionaryKey() = uniqueId to identifier

        private fun reportCsd(message: String) {
            println("[WARNING] CSD [$id] $message")
        }

        private val csdTranslations by lazy {
            CsdMatcherTranslations(
                gameCsdEngine(File(exportedDataDir, "$sourceExportDirName/tables/$sourceStatUnlabelledLanguage"), ::reportCsd),
                gameCsdEngine(File(exportedDataDir, "$targetExportDirName/tables/$targetLanguageKey"), ::reportCsd),
                ::reportCsd,
            )
        }

        private fun pairStatDescriptions(source: GameStatDescription, target: GameStatDescription) =
            csdTranslations.pair(source, sourceStatUnlabelledLanguage, target, targetLanguageKey)

        private val statsFromDescriptions: Map<String, Set<String>> by lazy {
            val enStatsFromGame = rawSourceStatsFromStatDescriptions
            val cnStatsFromGameById = rawTargetStatsFromStatDescriptions.associateBy { it.dictionaryKey() }
            buildMap<String, MutableSet<String>> statsMap@{
                enStatsFromGame
                    .filter {
                        // fishing_lure_type 英文名有 6 个, 中文名只有 4 个, 直接跳过
                        it.id != "fishing_lure_type"
                    }
                    .forEach { sourceDesc ->
                        // 源和目标可以来自不同导出目录，因此通过文件名和 id 匹配，不能依赖翻译后的文案。
                        val targetDesc = cnStatsFromGameById[sourceDesc.dictionaryKey()] ?: return@forEach
                        pairStatDescriptions(sourceDesc, targetDesc).forEach { (source, target) ->
                            getOrPut(source.uppercase()) { mutableSetOf() }.add(target)
                        }
                    }
            }
        }

        val preferredStats by lazy {
            buildMap {
                putAll(builtinSupportGems.map { it.key.uppercase() to setOf(it.value) }.toMap())
                putAll(grantsSupportEffectWithLevel.map { it.key.uppercase() to setOf(it.value) }.toMap())
                putAll(grantsEffectWithLevel.map { it.key.uppercase() to setOf(it.value) }.toMap())
                // 放在最后, 有些名词在不同地方翻译不同(例如: Caustic Retribution), 先以 stat_descriptions 为准
                putAll(statsFromDescriptions)
            }
        }

        val secondaryStats by lazy {
            extraStats
        }

        val reminderTexts by lazy {
            incursionRoomReminderTexts
        }
    }

    data class MercenarySupportTranslation(
        val name: String,
        val tier: Int,
    )
}
