package data.parser

import data.GameDataRepo
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtraStatsParserTest {
    @Test
    fun `supports multiple localized matcher sets`() {
        val stat = parse(
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
                    {"string": "法术暴击几率提高 #%"},
                    {"string": "法术暴击几率降低 #%", "negate": true}
                  ],
                  [
                    {"string": "法术暴击率提高 #%"},
                    {"string": "法术暴击率降低 #%", "negate": true}
                  ]
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(2, stat.translationSets.size)
        assertEquals(
            listOf("法术暴击几率提高 #%", "法术暴击几率降低 #%"),
            stat.translationSets[0].map { it.string }
        )
        assertEquals(
            listOf("法术暴击率提高 #%", "法术暴击率降低 #%"),
            stat.translationSets[1].map { it.string }
        )
    }

    @Test
    fun `treats legacy cn as one localized matcher set`() {
        val stat = parse(
            """
            [
              {
                "refName": "Legacy stat",
                "type": "DEFAULT",
                "en": [{"string": "legacy matcher"}],
                "cn": [{"string": "旧格式词缀"}]
              }
            ]
            """.trimIndent()
        )

        assertEquals(listOf(listOf("旧格式词缀")), stat.translationSets.map { it.map(Text::string) })
    }

    private fun parse(content: String): ExtraStat {
        val file = Files.createTempFile("extra-stats-parser-test", ".json")
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
