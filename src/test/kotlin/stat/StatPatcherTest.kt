package stat

import com.google.gson.JsonParser
import data.AptDataRepo
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun stat(ref: String, tradeType: String): AptDataRepo.Stat {
        val rawData = JsonParser.parseString(
            """
            {
              "ref": "$ref",
              "better": 1,
              "matchers": [{"string": "same matcher"}],
              "trade": {"ids": {"$tradeType": ["$tradeType.stat"]}}
            }
            """.trimIndent()
        ).asJsonObject
        return AptDataRepo.Stat(ref, rawData)
    }
}
