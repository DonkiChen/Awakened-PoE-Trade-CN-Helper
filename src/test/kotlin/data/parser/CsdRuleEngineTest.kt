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
    fun `support table and a non-jewel numeric formatter work through the same registry`() = withDirectory { dir ->
        dir.resolve("IndexableSupportGems.json").writeText("""[{"Index":12,"Name":"Unleash"}]""")
        val rules = parse(dir, """
            description
                2 level support
                2
                    # # "Supported by Level {0} {1}" display_indexable_support 2 1
                    # # "Cooldown: {0:+d}s" milliseconds_to_seconds_2dp 1 reminderstring ReminderTextExample
        """).single().rulesByLang.getValue("English")
        val engine = gameCsdEngine(dir) { error(it) }
        assertEquals("Supported by Level # Unleash", engine.tradeMatchers(rules[0]).single().text)
        assertEquals("Supported by Level 20 Unleash", engine.displayText(rules[0], listOf(20, 12)))
        assertEquals("Cooldown: +1.50s", engine.displayText(rules[1], listOf(1500, 0)))
        assertEquals("Cooldown: #s", engine.tradeMatchers(rules[1]).single().text)
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
