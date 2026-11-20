package stat

import data.parser.ModNameRow
import kotlin.test.Test
import kotlin.test.assertEquals

class ModNamePatcherTest {
    @Test
    fun `reads modifier groups without evaluating client strings`() {
        val content = """
            export default {
              MODIFIER_LINE: /.+/,
              SHAPER_MODS: ["The Shaper's", 'of Shaping'],
            }
        """.trimIndent()

        assertEquals(mapOf("SHAPER_MODS" to listOf("The Shaper's", "of Shaping")), ModNamePatcher.readGroups(content))
    }

    @Test
    fun `matches all English rows by name and joins localized rows by ID`() {
        val groups = mapOf("ELDER_MODS" to listOf("of the Elevated Elder", "missing"))
        val warnings = mutableListOf<String>()
        val translated = ModNamePatcher.collectNames(
            groups,
            listOf("a", "b", "c", "d").map { ModNameRow(it, "of the Elevated Elder") },
            listOf(
                ModNameRow("b", "高阶裂界者之"),
                ModNameRow("c", "<DA1>{尊崇裂界者之}"),
                ModNameRow("a", "尊崇裂界者之"),
                ModNameRow("unrelated", "其他"),
            ),
        ) { warnings.add(it) }

        assertEquals(mapOf("ELDER_MODS" to setOf("尊崇裂界者之", "高阶裂界者之")), translated)
        assertEquals(
            listOf(
                """ELDER_MODS: "of the Elevated Elder" missing 1 localized ID(s): d""",
                "ELDER_MODS: no English entry for \"missing\"",
            ),
            warnings,
        )
    }

    @Test
    fun `replaces old names without touching surrounding strings or line endings`() {
        val current = listOf(
            "export default {",
            "  // BEGIN GENERATED MOD NAMES",
            "  INFAMOUS_MODS: ['万恶的'],",
            "  // END GENERATED MOD NAMES",
            "  FOIL_UNIQUE: '古藏传奇',",
            "}",
            "",
        ).joinToString("\r\n")

        assertEquals(
            current.replace("['万恶的']", """["恶名的"]"""),
            ModNamePatcher.replaceGeneratedBlock(current, mapOf("INFAMOUS_MODS" to setOf("恶名的"))),
        )
    }
}
