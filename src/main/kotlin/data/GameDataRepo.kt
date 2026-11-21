package data

import data.parser.*
import stat.TradeCsdCandidate
import stat.TradeCsdOrigin
import stat.TradeStatHash
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

        private val modNameTables by lazy {
            val tables = File(exportedDataDir, "$targetExportDirName/tables")
            fun read(language: String): List<ModNameRow> =
                File(tables, "$language/Mods.json").reader().use { fromJson(it) }
            read("English") to read(targetLanguageKey)
        }

        internal fun readModNameTables() = modNameTables

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

        // Keep the game IDs so identical display names cannot silently overwrite one another.
        internal val effectMatcherTranslations by lazy {
            buildList {
                for ((table, template, predicate) in listOf(
                    Triple("ActiveSkills.json", "ModGrantsEffect", { row: BaseTableItem -> !row.id.endsWith("Royale", true) }),
                    Triple("BaseItemTypes.json", "ModGrantsSupportEffect", { row: BaseTableItem ->
                        row.id.startsWith("Metadata/Items/Gems/SupportGem") && !row.id.endsWith("Royale", true)
                    }),
                )) {
                    val templates = listOf(template, "${template}WithLevel").mapNotNull { key ->
                        clientStrings[key]?.let { (en, localized) -> en.text to localized.text }
                    }
                    if (templates.isEmpty()) continue
                    for ((en, localized) in parseTableDataToMapper(table, predicate)) {
                        if (en.name.isNullOrBlank() || localized.name.isNullOrBlank()) continue
                        for ((enTemplate, localizedTemplate) in templates) {
                            add(EffectMatcherTranslation(
                                gameId = en.id,
                                english = enTemplate.replace("{1}", "#").replace("{0}", en.name),
                                localized = localizedTemplate.replace("{1}", "#").replace("{0}", localized.name),
                            ))
                        }
                    }
                }
            }
        }

        val monsters by lazy {
            parseTableDataToTextMapper<BaseTableItem>("MonsterVarieties.json") {
                // 有些怪物在中文中的灵体名和野兽名不一样...
                !it.id.endsWith("Spectre")
            }
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

        private val lakeRooms by lazy {
            parseTableDataToTextMapper<BaseTableItem>("LakeRooms.json")
        }

        private val incursionRoomTables by lazy {
            parseTableDataToMapper<IncursionRoom>("IncursionRooms.json")
        }

        private val incursionRoomReminderTexts by lazy {
            incursionRoomTables
                .filter { !it.key.description.isNullOrBlank() && !it.value.description.isNullOrBlank() }
                .map { it.key.description!! to it.value.description!! }
                .toMap()
        }

        private val logbookFactions by lazy {
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

        private val veiledStats by lazy {
            val (english, localized) = readModNameTables()
            val localizedById = localized.associateBy { it.id }
            english.asSequence()
                .filter { "VeiledPrefix" in it.id || "VeiledSuffix" in it.id }
                .mapNotNull { source ->
                    val target = localizedById[source.id]?.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val matcher = source.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    matcher.uppercase() to target
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
        }

        private val clusterStats by lazy {
            val (source, target) = clientStrings["StatDescripotionTreeExpansionJewelGrantedSmallStat"]
                ?: return@lazy emptyMap()
            clusterJewelStats(
                File(exportedDataDir, "$sourceExportDirName/tables/$sourceStatUnlabelledLanguage"),
                File(exportedDataDir, "$targetExportDirName/tables/$targetLanguageKey"),
                rawSourceStatsFromStatDescriptions,
                rawTargetStatsFromStatDescriptions,
                sourceStatUnlabelledLanguage,
                targetLanguageKey,
                source.text,
                target.text,
                ::reportCsd,
            ).groupBy({ it.first.uppercase() }, { it.second }).mapValues { it.value.toSet() }
        }

        private val areaOptionStats by lazy {
            val roomTier = clientStrings["IncursionRoomPopupTier"]?.first?.text
            val lakeDifficulty = clientStrings["ItemDisplayLakeRoomDifficulty"]
            buildList {
                addAll(logbookFactions.toList())
                for ((source, target) in incursionRoomTables) {
                    add(source.name to target.name)
                    if (source.tier > 0 && roomTier != null) {
                        val tier = roomTier.replace("{0}", source.tier.toString())
                        add("${source.name} $tier" to target.name)
                        // Some trade-only room filters use the full label instead of an option name.
                        add("Has Room: ${source.name} $tier " to target.name)
                    }
                }
                if (lakeDifficulty != null) {
                    val (source, target) = lakeDifficulty
                    val en = legacyMatcherText(source.text)
                    val cn = legacyMatcherText(target.text)
                    for ((sourceName, targetName) in lakeRooms) {
                        if (sourceName.isNotBlank() && targetName.isNotBlank()) {
                            add("$sourceName ($en)" to "$targetName ($cn)")
                        }
                    }
                }
            }.filter { (source, target) -> source.isNotBlank() && target.isNotBlank() }
                .groupBy({ it.first.uppercase() }, { it.second })
                .mapValues { it.value.toSet() }
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

        private val influencedStats by lazy {
            buildMap<String, Set<String>> {
                for (id in listOf("ModDescriptionLineCleansingFireImplicit", "ModDescriptionLineGreatTangleImplicit")) {
                    val (source, target) = clientStrings[id] ?: continue
                    val matcher = legacyMatcherText(source.text).uppercase()
                    val translations = setOf(legacyMatcherText(target.text))
                    put(matcher, translations)
                    // APT's implicit-tier pseudo matchers include a trailing space.
                    put("$matcher ", translations)
                }
                for (id in listOf("InfluenceStatConditionPresenceCelestialBoss", "InfluenceStatConditionPresenceUniqueMonster")) {
                    val (source, target) = clientStrings[id] ?: continue
                    if ("{0}" !in source.text || "{0}" !in target.text) continue
                    for ((matcher, translations) in statsFromDescriptions) {
                        put(
                            source.text.uppercase().replace("{0}", matcher),
                            translations.map { target.text.replace("{0}", it) }.toSet(),
                        )
                    }
                }
            }
        }

        private fun GameStatDescription.dictionaryKey() = uniqueId to identifier

        private fun reportCsd(message: String) {
            System.err.println("[WARNING] CSD [$id] $message")
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

        private val tradeCsdByHash by lazy {
            rawSourceStatsFromStatDescriptions
                .filter { it.statIds.isNotEmpty() }
                .groupBy { TradeStatHash.compute(it.statIds) }
        }

        private val tradeTargetCsdByKey by lazy {
            rawTargetStatsFromStatDescriptions.groupBy { it.dictionaryKey() }
        }

        private val tradeCsdCandidates = mutableMapOf<Long, List<TradeCsdCandidate>>()

        private fun csdOrigin(source: GameStatDescription, target: GameStatDescription) = TradeCsdOrigin(
            id, sourceExportDirName, targetExportDirName, source.fileName,
            source.statIds, source.identifier, source.location.line, target.location.line,
        )

        internal fun tradeCandidates(hash: Long): List<TradeCsdCandidate> = tradeCsdCandidates.getOrPut(hash) {
            tradeCsdByHash[hash].orEmpty().flatMap { source ->
                tradeTargetCsdByKey[source.dictionaryKey()].orEmpty().map { target ->
                    TradeCsdCandidate(csdOrigin(source, target), pairStatDescriptions(source, target))
                }
            }
        }

        internal fun auditExports() = mapOf(
            "mapper" to id,
            "sourceExport" to sourceExportDirName,
            "targetExport" to targetExportDirName,
            "targetLanguage" to targetLanguageKey,
            "sourceCsdFiles" to rawSourceStatsFromStatDescriptions.map { it.fileName }.distinct().size,
            "targetCsdFiles" to rawTargetStatsFromStatDescriptions.map { it.fileName }.distinct().size,
        )

        private data class LegacyCsd(
            val translations: Map<String, Set<String>>,
            val origins: Map<String, List<Pair<String, TradeCsdOrigin>>>,
        )

        private val legacyCsd by lazy {
            val enStatsFromGame = rawSourceStatsFromStatDescriptions
            val cnStatsFromGameById = rawTargetStatsFromStatDescriptions.associateBy { it.dictionaryKey() }
            val origins = mutableMapOf<String, MutableList<Pair<String, TradeCsdOrigin>>>()
            val translations = buildMap<String, MutableSet<String>> {
                enStatsFromGame.forEach { sourceDesc ->
                    // 源和目标可以来自不同导出目录，因此通过文件名和 id 匹配，不能依赖翻译后的文案。
                    val targetDesc = cnStatsFromGameById[sourceDesc.dictionaryKey()] ?: return@forEach
                    val origin = csdOrigin(sourceDesc, targetDesc)
                    pairStatDescriptions(sourceDesc, targetDesc).forEach { (source, target) ->
                        val key = source.uppercase()
                        getOrPut(key) { mutableSetOf() }.add(target)
                        origins.getOrPut(key) { mutableListOf() }.add(target to origin)
                    }
                }
            }
            LegacyCsd(translations, origins)
        }

        private val statsFromDescriptions get() = legacyCsd.translations

        internal fun legacyCsdOrigins(source: String) = legacyCsd.origins[source.uppercase()].orEmpty()

        val preferredStats by lazy {
            buildMap {
                putAll(builtinSupportGems.map { it.key.uppercase() to setOf(it.value) }.toMap())
                putAll(grantsSupportEffectWithLevel.map { it.key.uppercase() to setOf(it.value) }.toMap())
                putAll(grantsEffectWithLevel.map { it.key.uppercase() to setOf(it.value) }.toMap())
                putAll(influencedStats)
                putAll(veiledStats)
                putAll(clusterStats)
                putAll(areaOptionStats)
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

internal data class EffectMatcherTranslation(val gameId: String, val english: String, val localized: String)
