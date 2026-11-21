package data.parser

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsdRuleEngineTest {
    @Test
    fun `lookup directives use the configured row key and skip non-Tempest mods`() = withDirectory { dir ->
        fun table(name: String, json: String) = dir.resolve("$name.json").writeText(json)
        table("FishingHookTypes", """[{"_index":0,"Index":4,"Name":"Iron Hook"}]""")
        table("ShaperApparitionAbility", """[{"_index":0,"Index":2,"Name":"Ire"}]""")
        table("MinionDoublingStatTypes", """[{"_index":0,"Index":6,"Name":"Zombies"}]""")
        table("ItemClasses", """[{"_index":7,"Name":"Bows"}]""")
        table("AfflictionRewardTypeVisuals", """[{"_index":5,"Name":"Currency"}]""")
        table("BaseItemTypes", """[{"_index":13,"Name":"Club"}]""")
        table("WeaponPassiveTreeUniqueBaseTypes", """[{"_index":11,"UniqueBase":13}]""")
        table("Mods", """[{"_index":1,"GenerationType":1,"Name":"Prefix"},{"_index":12,"GenerationType":8,"Name":"Tempest"}]""")
        val engine = gameCsdEngine(dir) { error(it) }
        val examples = listOf(
            Triple("fishing_hook_type", 4, "Iron Hook"),
            Triple("shaper_apparition_ability", 3, "Ire"),
            Triple("minion_doubling_type", 7, "Zombies"),
            Triple("mod_value_to_item_class", 7, "Bows"),
            Triple("affliction_reward_type", 5, "Currency"),
            Triple("weapon_tree_unique_base_type_name", 12, "Club"),
            Triple("tempest_mod_text", 12, "Tempest"),
        )
        for ((name, key, text) in examples) {
            val rule = StatDescriptionRule(listOf(StatCondition("#")), emptyList(), "{0}", listOf(name, "1"), CsdLocation("test", 1))
            assertEquals(text, engine.displayText(rule, listOf(key)), name)
            assertEquals(listOf(text), engine.tradeMatchers(rule).map { it.text }, name)
        }
    }

    @Test
    fun `jewel radius keeps both the localized lookup template and the literal override`() = withDirectory { dir ->
        val en = File(dir, "en").apply { mkdir() }
        val cn = File(dir, "cn").apply { mkdir() }
        val sizes = listOf("Small", "Medium", "Large", "VeryLarge", "Massive")
        fun writeSizes(tables: File, names: List<String>) = tables.resolve("ClientStrings.json").writeText(
            sizes.zip(names).joinToString(",", "[", "]") { (size, name) ->
                """{"Id":"JewelRadius$size","Text":"$name"}"""
            },
        )
        writeSizes(en, listOf("Small", "Medium", "Large", "Very Large", "Massive"))
        writeSizes(cn, listOf("小", "中", "大", "很大", "庞大"))
        val desc = parse(dir, """
            description
                1 ring_radius
                1
                    # "Only affects Passives in {0} Ring" passive_jewel_radius 1
                lang "Simplified Chinese"
                2
                    # "只影响 {0} 环形范围内的天赋" passive_jewel_radius 1
                    5 "只影响巨型圈内的天赋"
        """).single()
        val pairs = CsdMatcherTranslations(gameCsdEngine(en) { error(it) }, gameCsdEngine(cn) { error(it) }) {
            error(it)
        }.pair(desc, "English", desc, "Simplified Chinese")
        assertEquals(listOf("只影响 庞大 环形范围内的天赋", "只影响巨型圈内的天赋"),
            pairs.filter { it.first == "Only affects Passives in Massive Ring" }.map { it.second })
        assertEquals(listOf("Only affects Passives in Small Ring" to "只影响 小 环形范围内的天赋"),
            pairs.filter { it.first == "Only affects Passives in Small Ring" })
    }

    @Test
    fun `parse keeps named descriptions and ordered rules including unknown instructions`() = withDirectory { dir ->
        val rules = parse(dir, """
            include "Metadata/StatDescriptions/shared.csd"
            description named
                2 seed revision
                1
                    -10|# !0 gem_quality "A \"quoted\" value {0:+d}\nRevision {1}" other_instruction 1 alpha 2
                lang "Simplified Chinese"
                1
                    # # "old"

                lang "Simplified Chinese"
                1
                    # # "new"
            description tail
                1 next_stat
                0
        """)
        assertEquals(listOf("named", "tail"), rules.map { it.identifier })
        val first = rules.first()
        assertEquals(listOf("seed", "revision"), first.statIds)
        val row = first.rulesByLang.getValue("English").single()
        assertEquals(listOf("-10|#", "!0"), row.conditions.map { it.token })
        assertTrue(row.conditions[1].contains(1))
        assertFalse(row.conditions[1].contains(0))
        assertEquals(listOf("gem_quality"), row.restrictions)
        assertEquals("""A \"quoted\" value {0:+d}\nRevision {1}""", row.template)
        assertEquals(listOf("other_instruction", "1", "alpha", "2"), row.directiveTokens)
        assertEquals("new", first.rulesByLang.getValue("Simplified Chinese").single().template)
        assertEquals(5, row.location.line)
    }

    @Test
    fun `multi-stat lookup expands variants by faction and revision and supports literal overrides`() = withDirectory { dir ->
        val en = File(dir, "en").apply { mkdir() }
        val cn = File(dir, "cn").apply { mkdir() }
        writeJewels(en, "Vaal", "Eternal Empire", "Zerphi", "Mazcotl", "Cadiro")
        writeJewels(cn, "瓦尔", "永恒帝国", "泽佛伊", "马兹科特", "卡迪罗")
        val description = parse(dir, """
            description
                4 faction seed keystone revision
                2
                    1 # # # "Served {2} with {1}\nConquered by the {0}" alternate_tree_version_name 1 alternate_tree_version_keystone 3 1 4
                    5 # # # "Commissioned {1} coins for {2}\nConquered by the {0}" alternate_tree_version_name 1 alternate_tree_version_keystone 3 1 4 times_twenty 2
                lang "Simplified Chinese"
                3
                    5 # # # "为 {2} 铸造 {1} 枚金币\n被 {0} 抑制" alternate_tree_version_name 1 alternate_tree_version_keystone 3 1 4 times_twenty 2
                    1 # 2 1|# "侍奉马兹科特（旧版）共 {1}\n被瓦尔抑制"
                    1 # # # "侍奉 {2} 共 {1}\n被 {0} 抑制" alternate_tree_version_name 1 alternate_tree_version_keystone 3 1 4
        """).single()
        val diagnostics = mutableListOf<String>()
        val source = gameCsdEngine(en, diagnostics::add)
        val target = gameCsdEngine(cn, diagnostics::add)
        val result = CsdMatcherTranslations(source, target, diagnostics::add)
            .pair(description, "English", description, "Simplified Chinese")
            .toMap()
        assertEquals(mapOf(
            "Served Zerphi with #\nConquered by the Vaal" to "侍奉 泽佛伊 共 #\n被 瓦尔 抑制",
            "Served Mazcotl with #\nConquered by the Vaal" to "侍奉马兹科特（旧版）共 #\n被瓦尔抑制",
            "Commissioned # coins for Cadiro\nConquered by the Eternal Empire" to "为 卡迪罗 铸造 # 枚金币\n被 永恒帝国 抑制",
        ), result)
        val commission = description.rulesByLang.getValue("English")[1]
        assertEquals(
            "Commissioned 340 coins for Cadiro\nConquered by the Eternal Empire",
            source.displayText(commission, listOf(5, 17, 2, 3)),
        )
        assertNull(source.displayText(commission, listOf(1, 17, 2, 3)))
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    @Test
    fun `elder guardian lookup uses the general template and falls back to uncovered literals`() = withDirectory { dir ->
        val en = File(dir, "en").apply { mkdir() }
        val cn = File(dir, "cn").apply { mkdir() }
        en.resolve("ElderGuardians.json").writeText(
            """[{"_index":1,"Name":"The Eradicator"},{"_index":3,"Name":"The Purifier"}]""",
        )
        cn.resolve("ElderGuardians.json").writeText("""[{"_index":3,"Name":"裂界守卫：净世"}]""")
        val description = parse(dir, """
            description
                1 map_elder_boss_variation
                1
                    # "Map is occupied by {0}" elder_guardian_index 1
                lang "Simplified Chinese"
                3
                    # "地图被 {0} 占据" elder_guardian_index 1
                    2 "该地图被裂界守卫：寂灭占据"
                    4 "该地图被裂界守卫：净世占据"
        """).single()
        val pairs = CsdMatcherTranslations(gameCsdEngine(en) { error(it) }, gameCsdEngine(cn) { error(it) }) {
            error(it)
        }.pair(description, "English", description, "Simplified Chinese")
        assertEquals(mapOf(
            "Map is occupied by The Eradicator" to "该地图被裂界守卫：寂灭占据",
            "Map is occupied by The Purifier" to "地图被 裂界守卫：净世 占据",
        ), pairs.toMap())
    }

    @Test
    fun `random support gem rules pair localized names by gem index and keep the level variable`() = withDirectory { dir ->
        val en = File(dir, "en").apply { mkdir() }
        val cn = File(dir, "cn").apply { mkdir() }
        en.resolve("IndexableSupportGems.json").writeText("""
            [{"_index":0,"Index":12,"Name":"Unleash"},{"_index":1,"Index":128,"Name":"Added Chaos Damage"}]
        """.trimIndent())
        cn.resolve("IndexableSupportGems.json").writeText("""
            [{"_index":0,"Index":128,"Name":"附加混沌伤害"},{"_index":1,"Index":12,"Name":"释出"}]
        """.trimIndent())
        val description = parse(dir, """
            description
                2 local_random_support_gem_level local_random_support_gem_index
                1
                    # # "Socketed Gems are Supported by Level {0} {1}" display_indexable_support 2 1
                lang "Traditional Chinese"
                1
                    # # "插入的技能石被 {0} 级的{1}辅助" display_indexable_support 2 1
        """).single()
        val source = gameCsdEngine(en) { error(it) }
        val target = gameCsdEngine(cn) { error(it) }
        assertEquals(mapOf(
            "Socketed Gems are Supported by Level # Unleash" to "插入的技能石被 # 级的释出辅助",
            "Socketed Gems are Supported by Level # Added Chaos Damage" to "插入的技能石被 # 级的附加混沌伤害辅助",
        ), CsdMatcherTranslations(source, target) { error(it) }
            .pair(description, "English", description, "Traditional Chinese").toMap())
        assertEquals(
            "插入的技能石被 20 级的释出辅助",
            target.displayText(description.rulesByLang.getValue("Traditional Chinese").single(), listOf(20, 12)),
        )
    }

    @Test
    fun `pelagic pearl rules pair slot and support indexes and prefer literal translations`() = withDirectory { dir ->
        val en = File(dir, "en").apply { mkdir() }
        val cn = File(dir, "cn").apply { mkdir() }
        fun writeTables(tables: File, slots: List<String>, support: String) {
            val rows = listOf("Helmet", "Gloves", "Boots", "PassiveTree").zip(slots).map { (id, text) ->
                """{"Id":"PelagicPearlSlotType$id","Text":"$text"}"""
            }
            tables.resolve("ClientStrings.json").writeText(rows.reversed().joinToString(",", "[", "]"))
            tables.resolve("IndexableNonActiveSupportGems.json").writeText("""[{"Index":126,"Name":"$support"}]""")
            tables.resolve("IndexableSupportGems.json").writeText("""[{"Index":126,"Name":"Wrong Table"}]""")
        }
        writeTables(en, listOf("Helmet", "Gloves", "Boots", "Passive Tree"), "Added Chaos Damage")
        writeTables(cn, listOf("头盔", "手套", "靴子", "天赋树"), "附加混沌伤害")
        val description = parse(dir, """
            description
                3 local_pearl_random_support_gem_1_slot_index local_pearl_random_support_gem_1_index local_pearl_random_support_gem_1_level
                2
                    4 # # "Skills granted by your {0} are Supported by level {2} {1}" pelagic_pearl_slot 1 display_indexable_non_active_support 2 3
                    # # # "Skills Socketed in your {0} are Supported by level {2} {1}" pelagic_pearl_slot 1 display_indexable_non_active_support 2 3
                lang "Traditional Chinese"
                4
                    4 # # "{0} 给予你的技能由 {2} 级的 {1} 辅助" pelagic_pearl_slot 1 display_indexable_non_active_support 2 3
                    # # # "你的 {0} 中镶嵌的技能由 {2} 级的 {1} 辅助" pelagic_pearl_slot 1 display_indexable_non_active_support 2 3
                    3 # # "你鞋子中镶嵌的技能由 {2} 级的{1}辅助" display_indexable_non_active_support 2 3
                    4 # # "你天赋树赋予的技能由 {2} 级的{1}辅助" display_indexable_non_active_support 2 3
        """).single()
        val source = gameCsdEngine(en) { error(it) }
        val target = gameCsdEngine(cn) { error(it) }
        assertEquals(mapOf(
            "Skills Socketed in your Helmet are Supported by level # Added Chaos Damage" to "你的 头盔 中镶嵌的技能由 # 级的 附加混沌伤害 辅助",
            "Skills Socketed in your Gloves are Supported by level # Added Chaos Damage" to "你的 手套 中镶嵌的技能由 # 级的 附加混沌伤害 辅助",
            "Skills Socketed in your Boots are Supported by level # Added Chaos Damage" to "你鞋子中镶嵌的技能由 # 级的附加混沌伤害辅助",
            "Skills granted by your Passive Tree are Supported by level # Added Chaos Damage" to "你天赋树赋予的技能由 # 级的附加混沌伤害辅助",
            "Skills Socketed in your Passive Tree are Supported by level # Added Chaos Damage" to "你天赋树赋予的技能由 # 级的附加混沌伤害辅助",
        ), CsdMatcherTranslations(source, target) { error(it) }
            .pair(description, "English", description, "Traditional Chinese").toMap())
    }

    @Test
    fun `random skill gem lookup accepts its selector as a literal rather than a stat index`() = withDirectory { dir ->
        val en = File(dir, "en").apply { mkdir() }
        val cn = File(dir, "cn").apply { mkdir() }
        en.resolve("IndexableSkillGems.json").writeText("""[{"Index":37,"Name1":"Fireball","Name2":"Wrong"}]""")
        cn.resolve("IndexableSkillGems.json").writeText("""[{"Index":37,"Name1":"火球","Name2":"错误"}]""")
        val description = parse(dir, """
            description
                2 random_skill_gem_level_+_level random_skill_gem_level_+_index
                1
                    1|# # "+{0} to Level of all {1} Gems" display_indexable_skill 2 0
                lang "Traditional Chinese"
                1
                    1|# # "所有 {1} 宝石等级 +{0}" display_indexable_skill 2 0
        """).single()
        val source = gameCsdEngine(en) { error(it) }
        val target = gameCsdEngine(cn) { error(it) }
        assertEquals(
            listOf("# to Level of all Fireball Gems" to "所有 火球 宝石等级 #"),
            CsdMatcherTranslations(source, target) { error(it) }
                .pair(description, "English", description, "Traditional Chinese"),
        )
        val rule = description.rulesByLang.getValue("English").single()
        assertEquals("+2 to Level of all Fireball Gems", source.displayText(rule, listOf(2, 37)))
        val diagnostics = mutableListOf<String>()
        assertTrue(gameCsdEngine(en, diagnostics::add)
            .tradeMatchers(rule.copy(directiveTokens = listOf("display_indexable_skill", "2", "1"))).isEmpty())
        assertTrue(diagnostics.single().contains("requires trailing literal '0'"))
    }

    @Test
    fun `game lookups use passive hashes and map variants rather than row order`() = withDirectory { dir ->
        dir.resolve("PassiveSkills.json").writeText("""[{"_index":7,"PassiveSkillGraphId":29199,"Name":"Iron Grip"}]""")
        dir.resolve("ElderGuardians.json").writeText("""[{"_index":3,"Name":"The Purifier"}]""")
        dir.resolve("AtlasExiles.json").writeText("""[{"_index":1,"CitadelName":"Veritania's Citadel"}]""")
        dir.resolve("FishingLureTypes.json").writeText("""[{"_index":0,"Index":3,"Name":"Totemic Wood Lure"}]""")
        val rules = parse(dir, """
            description
                1 stat
                4
                    # "Allocates {}" passive_hash 1
                    # "Map is occupied by {0}" elder_guardian_index 1
                    # "Map contains {0}" atlas_exile_citadel_index 1
                    # "{0}" fishing_lure_type 1
        """).single().rulesByLang.getValue("English")
        val engine = gameCsdEngine(dir) { error(it) }
        assertEquals("Allocates Iron Grip", engine.displayText(rules[0], listOf(29199)))
        assertEquals("Map is occupied by The Purifier", engine.displayText(rules[1], listOf(4)))
        assertEquals("Map contains Veritania's Citadel", engine.displayText(rules[2], listOf(2)))
        assertEquals("Totemic Wood Lure", engine.displayText(rules[3], listOf(3)))
    }

    @Test
    fun `legacy matcher understands implicit and explicit numeric format slots`() {
        assertEquals("#% chance to Flee", legacyMatcherText("{:d}% chance to Flee"))
        assertEquals("# to Strength; # to Dexterity", legacyMatcherText("{:+d} to Strength; {10:+d} to Dexterity"))
    }

    @Test
    fun `numeric formatter keeps precision for display but uses a wildcard for matching`() = withDirectory { dir ->
        val rule = parse(dir, """
            description
                1 cooldown
                1
                    # "Cooldown: {0:+d}s" milliseconds_to_seconds_2dp 1 reminderstring ReminderTextExample
        """).single().rulesByLang.getValue("English").single()
        val engine = gameCsdEngine(dir) { error(it) }
        assertEquals("Cooldown: +1.50s", engine.displayText(rule, listOf(1500)))
        assertEquals("Cooldown: #s", engine.tradeMatchers(rule).single().text)
    }

    @Test
    fun `matcher template keeps multi-digit and reordered slots as distinct numeric placeholders`() = withDirectory { dir ->
        val rule = parse(dir, """
            description
                11 a b c d e f g h i j k
                1
                    # # # # # # # # # # # "Second {10:d}, first {0:+d}\n[Tooltip::7|visible {1}]"
        """).single().rulesByLang.getValue("English").single()
        val engine = CsdRuleEngine(emptyMap()) { error(it) }
        val matcher = engine.tradeMatchers(rule).single()
        assertEquals("Second #, first #\nvisible #", matcher.text)
        assertEquals(setOf(10, 0, 1), matcher.placeholderSlots)
        assertEquals("Second 12, first +2\nvisible 3", engine.displayText(rule, (2..12).toList()))
    }

    @Test
    fun `unknown directives and missing tables report location and produce no matcher`() = withDirectory { dir ->
        val description = parse(dir, """
            description
                1 stat
                3
                    # "Unknown {0}" mystery 1
                    # "Missing {0}" alternate_tree_version_name 1
                    # "Normal {0}"
        """).single()
        val diagnostics = mutableListOf<String>()
        val rules = description.rulesByLang.getValue("English")
        val engine = gameCsdEngine(dir, diagnostics::add)
        assertEquals(listOf("Normal #"), rules.flatMap(engine::tradeMatchers).map { it.text })
        assertTrue(diagnostics.any { "sample.csd:4:" in it && "unsupported directive 'mystery'" in it })
        assertTrue(diagnostics.any { "sample.csd:5:" in it && "missing table" in it })
    }

    @Test
    fun `ambiguity and incomplete domains are not resolved by row order`() = withDirectory { dir ->
        val description = parse(dir, """
            description
                2 id revision
                1
                    # # "From {0}" example 1 2
                lang "Simplified Chinese"
                3
                    1 # "First"
                    1 # "Second"
                    2 1 "Partial"
        """).single()
        val diagnostics = mutableListOf<String>()
        val lookup = CsdDirective.Lookup(2) {
            listOf(CsdLookupRow(listOf(1..1, allStatValues), "A"), CsdLookupRow(listOf(2..2, allStatValues), "B"))
        }
        val engine = CsdRuleEngine(mapOf("example" to lookup), diagnostics::add)
        assertEquals(emptyList(), CsdMatcherTranslations(engine, engine, diagnostics::add)
            .pair(description, "English", description, "Simplified Chinese"))
        assertTrue(diagnostics.any { "ambiguous targets" in it })
        assertTrue(diagnostics.any { "covers only part" in it })
    }

    @Test
    fun `different numeric identities with the same source text do not create conflicting matchers`() = withDirectory { dir ->
        val description = parse(dir, """
            description
                1 stat
                1
                    # "From {0}" example 1
                lang "Simplified Chinese"
                2
                    1 "第一"
                    2 "第二"
        """).single()
        val diagnostics = mutableListOf<String>()
        val lookup = CsdDirective.Lookup(1) {
            listOf(CsdLookupRow(listOf(1..1), "same"), CsdLookupRow(listOf(2..2), "same"))
        }
        val engine = CsdRuleEngine(mapOf("example" to lookup), diagnostics::add)
        assertEquals(emptyList(), CsdMatcherTranslations(engine, engine, diagnostics::add)
            .pair(description, "English", description, "Simplified Chinese"))
        assertTrue(diagnostics.any { "source matcher has conflicting variants" in it })
    }

    @Test
    fun `unsupported row does not shift ordinary translations`() = withDirectory { dir ->
        val description = parse(dir, """
            description
                1 stat
                2
                    1 "First" unknown 1
                    2 "Second {0}"
                lang "Simplified Chinese"
                2
                    1 "第一"
                    2 "第二 {0}"
        """).single()
        val engine = gameCsdEngine(dir) {}
        assertEquals(listOf("Second #" to "第二 #"), CsdMatcherTranslations(engine, engine) {}
            .pair(description, "English", description, "Simplified Chinese"))
    }

    private fun parse(dir: File, text: String): List<GameStatDescription> {
        dir.resolve("sample.csd").writeText(text.trimIndent(), Charsets.UTF_16)
        return StatDescriptionParsers.parse(dir)
    }

    private fun writeJewels(dir: File, first: String, second: String, old: String, current: String, cadiro: String) {
        dir.resolve("AlternateTreeVersions.json").writeText("""[{"_index":1,"Name":"$first"},{"_index":5,"Name":"$second"}]""")
        dir.resolve("AlternateTreeVersionKeystones.json").writeText("""
            [
                {"Version":1,"StatValue":2,"InternalVersion":0,"Conqueror":"$old"},
                {"Version":1,"StatValue":2,"InternalVersion":1,"Conqueror":"$current"},
                {"Version":5,"StatValue":2,"InternalVersion":0,"Conqueror":"$cadiro"}
            ]
        """.trimIndent())
    }

    private inline fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("csd-rules-test").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
