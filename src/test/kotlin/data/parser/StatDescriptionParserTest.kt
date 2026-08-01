package data.parser

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatDescriptionParserTest {
    @Test
    fun `unlabelled descriptions use the configured default language`() {
        val description = parse(
            """
            description
            1 sample_stat
            1
                # "简体中文文案"
            """.trimIndent(),
            unlabelledLanguage = "Simplified Chinese"
        )

        assertEquals(
            listOf("简体中文文案"),
            description.namesByLang["Simplified Chinese"]
        )
        assertNull(description.namesByLang["English"])
    }

    @Test
    fun `explicit language blocks remain separate from the default language`() {
        val description = parse(
            """
            description
            1 sample_stat
            1
                # "English text"
            lang "Simplified Chinese"
            1
                # "简体中文文案"
            """.trimIndent(),
            unlabelledLanguage = "English"
        )

        assertEquals(listOf("English text"), description.namesByLang["English"])
        assertEquals(
            listOf("简体中文文案"),
            description.namesByLang["Simplified Chinese"]
        )
    }

    private fun parse(content: String, unlabelledLanguage: String): GameStatDescription {
        val directory = Files.createTempDirectory("stat-description-parser-test")
        try {
            directory.resolve("test_stat_descriptions.txt").writeText(content, Charsets.UTF_16)
            return StatDescriptionParsers.parse(directory.toFile(), unlabelledLanguage).single()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
