package stat

import com.google.gson.JsonParser
import data.AptDataRepo
import data.GameDataRepo
import data.parser.ExtraStat
import data.parser.parseExtraStats
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StatPatcherTest {
    @Test
    fun `merges configured localized matcher collision`() {
        val general = stat(
            ref = "#% increased Elemental Damage",
            tradeType = "implicit"
        )
        val unrelated = stat(
            ref = "#% increased Fire Damage",
            tradeType = "explicit"
        )
        val enchant = stat(
            ref = "Has #% increased Elemental Damage",
            tradeType = "enchant"
        )

        val normalized = StatPatcher.mergeTrivialGroups(
            listOf(general, unrelated, enchant)
        )

        assertEquals(2, normalized.size)
        val group = assertIs<AptDataRepo.StatGroup>(normalized[0])
        assertEquals(
            "trivial-merge",
            group.rawData.getAsJsonObject("resolve").get("strat").asString
        )
        assertEquals(
            listOf(
                "#% increased Elemental Damage",
                "Has #% increased Elemental Damage"
            ),
            group.stats.map { it.refName }
        )
        assertEquals("#% increased Fire Damage", (normalized[1] as AptDataRepo.Stat).refName)
    }

    @Test
    fun `merges configured semantic aliases`() {
        val rules = listOf(
            listOf(
                "#% reduced Elemental Damage taken while stationary",
                "#% reduced Elemental Damage Taken while stationary"
            ),
            listOf("Cannot be Chilled", "Immune to Chill"),
            listOf("Lose # Mana per second", "Lose # Mana per Second"),
            listOf(
                "Socketed Gems are supported by Level # Chance to Bleed",
                "Socketed Gems are Supported by Level # Chance To Bleed"
            ),
            listOf("#% increased Physical Damage", "Deal no Physical Damage")
        )

        for (rule in rules) {
            val normalized = StatPatcher.mergeTrivialGroups(
                rule.map { stat(ref = it, tradeType = "explicit") }
            )

            val group = assertIs<AptDataRepo.StatGroup>(normalized.single())
            assertEquals(rule, group.stats.map { it.refName })
        }
    }

    @Test
    fun `does not merge unconfigured duplicate matchers`() {
        val first = stat(
            ref = "First stat",
            tradeType = "explicit"
        )
        val second = stat(
            ref = "Second stat",
            tradeType = "explicit"
        )

        val normalized = StatPatcher.mergeTrivialGroups(listOf(first, second))

        assertEquals(listOf(first, second), normalized)
    }

    @Test
    fun `copies advanced matcher for additional translations`() {
        val stat = stat(
            ref = "Advanced stat",
            tradeType = "explicit",
            matcherString = "base matcher",
            advanced = "base advanced"
        )

        StatPatcher.doReplace(
            cnMatcherNames = setOf("first matcher", "second matcher"),
            cnAdvancedNames = setOf("first advanced", "second advanced"),
            stat = stat,
            matcher = stat.matchers.single()
        )

        val matchers = stat.rawData.getAsJsonArray("matchers").map { it.asJsonObject }
        assertEquals(
            listOf("first matcher", "first matcher", "second matcher", "second matcher"),
            matchers.map { it.get("string").asString }
        )
        assertEquals(
            listOf("first advanced", "second advanced", "first advanced", "second advanced"),
            matchers.map { it.get("advanced").asString }
        )
    }

    @Test
    fun `collects every localized matcher set with matching negate`() {
        val extraStat = extraStat(
            """
            [
              {
                "refName": "Spell critical chance",
                "type": "DEFAULT",
                "resolver": "MERGE",
                "en": [
                  {"string": "increased spell critical chance"},
                  {"string": "reduced spell critical chance", "negate": true}
                ],
                "cns": [
                  [
                    {"string": "提高一"},
                    {"string": "降低一", "negate": true}
                  ],
                  [
                    {"string": "提高二"},
                    {"string": "降低二", "negate": true}
                  ]
                ]
              }
            ]
            """.trimIndent()
        )
        val negativeStat = stat(
            ref = "Spell critical chance",
            tradeType = "explicit",
            matcherString = "reduced spell critical chance",
            negate = true
        )

        val (matcherNames, advancedNames) = StatPatcher.collectExtraStatTranslations(
            listOf(extraStat),
            negativeStat.matchers.single()
        )

        assertEquals(setOf("降低一", "降低二"), matcherNames)
        assertEquals(emptySet(), advancedNames)
    }

    @Test
    fun `rejects a replacement without a matcher translation`() {
        val stat = stat(
            ref = "Untranslated stat",
            tradeType = "explicit",
            matcherString = "original matcher"
        )

        val replaced = StatPatcher.doReplace(
            cnMatcherNames = setOf("original matcher"),
            cnAdvancedNames = setOf("translated advanced"),
            stat = stat,
            matcher = stat.matchers.single()
        )

        assertFalse(replaced)
        assertEquals(
            "original matcher",
            stat.matchers.single().rawData.get("string").asString
        )
    }

    @Test
    fun `rejects a mapping identical to the English matcher`() {
        val stat = stat(
            ref = "Untranslated stat",
            tradeType = "explicit"
        )

        val replaced = StatPatcher.doReplace(
            cnMatcherNames = setOf("same matcher"),
            cnAdvancedNames = emptySet(),
            stat = stat,
            matcher = stat.matchers.single()
        )

        assertFalse(replaced)
    }

    @Test
    fun `removes a matcher without a translation`() {
        val stat = stat(
            ref = "Untranslated stat",
            tradeType = "explicit"
        )

        stat.removeMatcher(stat.matchers.single())

        assertEquals(0, stat.rawData.getAsJsonArray("matchers").size())
    }

    @Test
    fun `reads matchers after replacing the raw matcher array`() {
        val stat = stat(
            ref = "Translated stat",
            tradeType = "explicit"
        )
        val replacement = JsonParser.parseString(
            "{\"string\": \"translated matcher\"}"
        ).asJsonObject

        stat.replaceMatchers(listOf(replacement))

        assertEquals("translated matcher", stat.matchers.single().string)
    }

    private fun stat(
        ref: String,
        tradeType: String,
        matcherString: String = "same matcher",
        advanced: String? = null,
        negate: Boolean = false
    ): AptDataRepo.Stat {
        val negateJson = if (negate) ", \"negate\": true" else ""
        val matcher = if (advanced == null) {
            "{\"string\": \"$matcherString\"$negateJson}"
        } else {
            "{\"string\": \"$matcherString\", \"advanced\": \"$advanced\"$negateJson}"
        }
        val rawData = JsonParser.parseString(
            """
            {
              "ref": "$ref",
              "better": 1,
              "matchers": [$matcher],
              "trade": {"ids": {"$tradeType": ["$tradeType.stat"]}}
            }
            """.trimIndent()
        ).asJsonObject
        return AptDataRepo.Stat(ref, rawData)
    }

    private fun extraStat(content: String): ExtraStat {
        val file = Files.createTempFile("extra-stats-patcher-test", ".json")
        try {
            file.writeText(content)
            return parseExtraStats(
                GameDataRepo.GameDataMapper("", "", "", "", ""),
                file.toFile()
            ).single()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
