package stat

import com.google.gson.JsonParser
import data.AptDataRepo
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeStatResolverTest {
    private val life = listOf("base_maximum_life")
    private val fire = listOf("attack_minimum_added_fire_damage", "attack_maximum_added_fire_damage")

    @Test
    fun `computes unsigned trade hashes from ordered stat IDs`() {
        assertEquals(3299347043L, TradeStatHash.compute(life))
        assertEquals(1573130764L, TradeStatHash.compute(fire))
        assertEquals(1540144614L, TradeStatHash.compute(fire.reversed()))
    }

    @Test
    fun `same English text is resolved in the selected trade hash bucket only`() {
        val candidates = listOf(
            candidate(life, "Shared #" to "生命 #"),
            candidate(fire, "Shared #" to "火焰 #"),
        ).groupBy { TradeStatHash.compute(it.origin.statIds) }
        fun translate(id: String): String? {
            val hashes = TradeStatResolver.hashes(listOf(id))
            return TradeStatResolver.resolve(
                matcher("""{"string":"Shared #"}"""), hashes, hashes.flatMap { candidates[it].orEmpty() }, { it },
            ).accepted.singleOrNull()?.translation?.string
        }
        assertEquals("生命 #", translate("explicit.stat_3299347043"))
        assertEquals("火焰 #", translate("implicit.stat_1573130764"))
    }

    @Test
    fun `same hash can render distinct matchers without losing fixed value or advanced text`() {
        val original = matcher("""{"string":"One thing","advanced":"One thing (advanced)","value":1,"ref":"matcher-ref"}""")
        val hashes = listOf(TradeStatHash.compute(life))
        val candidates = listOf(
            candidate(life, "# things" to "# 项", "One thing" to "一项"),
            candidate(life, "One thing (advanced)" to "一项（进阶）", file = "advanced.csd"),
            candidate(fire, "One thing (advanced)" to "不相关", file = "advanced-other.csd"),
        )
        val result = TradeStatResolver.resolve(original, hashes, candidates, { it })
        assertEquals("id-disambiguated", result.status)
        val selected = result.accepted.single()
        assertEquals(listOf("stat.csd", "advanced.csd"), selected.origins.map { it.file })
        assertEquals(
            JsonParser.parseString("""{"string":"一项","advanced":"一项（进阶）","value":1,"ref":"matcher-ref"}"""),
            TradeStatResolver.apply(original, selected.translation),
        )
        val plural = TradeStatResolver.resolve(matcher("""{"string":"# things"}"""), hashes, candidates, { it })
        assertEquals("# 项", plural.accepted.single().translation.string)
    }

    @Test
    fun `disagreeing candidates are a conflict in either order`() {
        val candidates = listOf(candidate(life, "Shared" to "甲"), candidate(life, "Shared" to "乙", file = "other.csd"))
        for (ordered in listOf(candidates, candidates.reversed())) {
            val result = TradeStatResolver.resolve(matcher("""{"string":"Shared"}"""), listOf(3299347043L), ordered, { it })
            assertEquals("conflict", result.status)
            assertEquals(emptyList(), result.accepted)
            assertEquals(setOf("甲", "乙"), result.matches.map { it.translation.string }.toSet())
        }
    }

    @Test
    fun `different exports can contribute aliases for the same verified internal stat`() {
        val first = candidate(life, "Shared" to "甲")
        val second = candidate(life, "Shared" to "乙", mapper = "other")
        val original = matcher("""{"string":"Shared"}""")
        val result = TradeStatResolver.resolve(original, listOf(3299347043L), listOf(first, second), { it })
        assertEquals("id", result.status)
        assertEquals(setOf("甲", "乙"), result.accepted.map { it.translation.string }.toSet())

        val unrelated = candidate(fire, "Shared" to "乙", mapper = "other")
        val conflict = TradeStatResolver.resolve(original, listOf(3299347043L, 1573130764L), listOf(first, unrelated), { it })
        assertEquals("conflict", conflict.status)
        assertEquals(emptyList(), conflict.accepted)
    }

    @Test
    fun `configured CSD alias requires both the trade ID and the exact ordered CSD stat group`() {
        val id = "explicit.stat_3299347043"
        val aliases = listOf(TradeCsdAlias(id, fire))
        val original = matcher("""{"string":"Shared #"}""")
        val candidates = listOf(
            candidate(fire, "Shared #" to "已验证 #", "Other #" to "其他 #"),
            candidate(fire.reversed(), "Shared #" to "反序 #"),
            candidate(life, "Shared #" to "无关 #"),
        )
        fun translations(ids: List<String>) = TradeStatResolver.resolveAliases(
            original, ids, aliases, { candidates }, { it },
        ).map { it.translation.string }

        assertEquals(listOf("已验证 #"), translations(listOf(id)))
        assertEquals(emptyList(), translations(listOf("implicit.stat_3299347043")))
    }

    @Test
    fun `fixed options bypass numeric lookup and keep the full trade query metadata`() {
        val raw = JsonParser.parseString(
            """{"ref":"Option","matchers":[{"string":"Option","value":1}],"trade":{"ids":{"enchant":["enchant.stat_3299347043|7"]},"option":true}}"""
        ).asJsonObject
        val stat = AptDataRepo.Stat("Option", raw)
        assertEquals(listOf("enchant.stat_3299347043|7"), stat.tradeIds)
        assertEquals(emptyList(), TradeStatResolver.hashes(stat.tradeIds))
        assertEquals(emptyList(), TradeStatResolver.hashes(listOf("explicit.pseudo_timeless_jewel_doryani")))
        assertEquals(emptyList(), TradeStatResolver.hashes(listOf("pseudo.stat_3299347043")))
        assertEquals(listOf(3299347043L), TradeStatResolver.hashes(listOf("implicit.stat_3299347043", "pseudo.pseudo_maximum_life")))
        val cloned = stat.deepClone()
        cloned.replaceMatchers(listOf(TradeStatResolver.apply(stat.matchers.single(), TradeStatResolver.Translation("选项", null))))
        assertEquals(stat.rawData["trade"], cloned.rawData["trade"])
        assertEquals(stat.rawData["ref"], cloned.rawData["ref"])
        assertEquals(1, cloned.matchers.single().rawData["value"].asInt)
    }

    private fun matcher(json: String): AptDataRepo.Stat.Matcher {
        val raw = JsonParser.parseString(json).asJsonObject
        return AptDataRepo.Stat.Matcher(raw["string"].asString, raw["advanced"]?.asString, raw)
    }

    private fun candidate(ids: List<String>, vararg pairs: Pair<String, String>, file: String = "stat.csd", mapper: String = "test") =
        TradeCsdCandidate(TradeCsdOrigin(mapper, "source", "target", file, ids, null, 1, 1), pairs.toList())
}
