package data.parser

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterJewelStatsTest {
    @Test
    fun `small passives render concrete values in game order and use passive-specific text`() {
        val dir = Files.createTempDirectory("cluster-stats").toFile()
        try {
            dir.resolve("PassiveTreeExpansionSkills.json").writeText("""[{"PassiveSkillsKey":9},{"PassiveSkillsKey":10}]""")
            dir.resolve("PassiveSkills.json").writeText("""
                [{"_index":9,"Stats":[22,5],"Stat1Value":12,"Stat2Value":4},
                 {"_index":10,"Stats":[5,7],"Stat1Value":4,"Stat2Value":2}]
            """.trimIndent())
            dir.resolve("Stats.json").writeText("""
                [{"_index":22,"Id":"damage"},{"_index":5,"Id":"strength"},{"_index":7,"Id":"missing"}]
            """.trimIndent())
            dir.resolve("stat_descriptions.csd").writeText("""
                description
                    1 strength
                    1
                        # "{:+d} Strength"
                    lang "Traditional Chinese"
                    1
                        # "{:+d} 力量"
                description
                    1 damage
                    1
                        # "{0}% increased Global Damage"
                    lang "Traditional Chinese"
                    1
                        # "全域伤害提高 {0}%"
            """.trimIndent(), Charsets.UTF_16)
            dir.resolve("passive_skill_stat_descriptions.csd").writeText("""
                description
                    1 damage
                    1
                        # "{0}% increased Damage"
                    lang "Traditional Chinese"
                    1
                        # "伤害提高 {0}%"
            """.trimIndent(), Charsets.UTF_16)
            val descriptions = StatDescriptionParsers.parse(dir)
            val diagnostics = mutableListOf<String>()
            assertEquals(
                listOf("Grants: +4 Strength\nGrants: 12% increased Damage" to "获得：+4 力量\n获得：伤害提高 12%"),
                clusterJewelStats(
                    dir, dir, descriptions, descriptions, "English", "Traditional Chinese",
                    "Grants: {0}", "获得：{0}", diagnostics::add,
                ),
            )
            assertTrue(diagnostics.single().contains("passive row 10"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
