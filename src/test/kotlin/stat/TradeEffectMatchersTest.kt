package stat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import data.AptDataRepo
import data.EffectMatcherTranslation
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeEffectMatchersTest {
    private val explicit = "explicit.effect_3467858890"
    private val implicit = "implicit.effect_3467858890"

    private fun stat(ref: String, ids: List<String>, vararg matchers: String): AptDataRepo.Stat {
        val tradeIds = JsonObject()
        for (id in ids) {
            val type = id.substringBefore('.')
            val group = tradeIds.getAsJsonArray(type) ?: JsonArray().also { tradeIds.add(type, it) }
            group.add(id)
        }
        val data = JsonParser.parseString("""{"ref":"$ref","trade":{},"matchers":[${matchers.joinToString(",")}]}""")
            .asJsonObject
        data.getAsJsonObject("trade").add("ids", tradeIds)
        return AptDataRepo.Stat(ref, data)
    }

    @Test
    fun `appends a verified fixed value alias without replacing the existing translation`() {
        val source = stat("Call", listOf(explicit),
            """{"string":"Grants Level # Call of Steel"}""",
            """{"string":"Grants ​Call of Steel","value":1}""",
        )
        val target = stat("Call", listOf(explicit), """{"string":"赋予 # 级 钢铁呼唤"}""")
        val translations = listOf(EffectMatcherTranslation("call_of_steel", "Grants Call of Steel", "赋予 ​钢铁呼唤"))
        val decisions = mutableListOf<TradeEffectMatchers.Decision>()
        repeat(2) {
            TradeEffectMatchers.supplement(listOf(source), listOf(target), translations, decisions::add)
        }

        assertEquals(listOf("赋予 # 级 钢铁呼唤", "赋予 钢铁呼唤"), target.matchers.map { it.string })
        assertEquals(1, target.matchers.last().rawData["value"].asInt)
        assertEquals(listOf("added"), decisions.filter { it.status != "no-client-matcher" }.map { it.status })
        assertEquals(2, source.matchers.size)
    }

    @Test
    fun `skips a shared English display name when game IDs disagree`() {
        val ids = listOf(explicit, implicit)
        val source = stat("Call", ids, """{"string":"Grants Call of Steel","value":1}""")
        val target = stat("Call", ids, """{"string":"赋予 # 级 钢铁呼唤"}""")
        val translations = listOf(
            EffectMatcherTranslation("call_of_steel", "Grants Call of Steel", "赋予 钢铁呼唤"),
            EffectMatcherTranslation("another_skill", "Grants Call of Steel", "赋予 另一个技能"),
        )
        val statuses = mutableListOf<String>()
        TradeEffectMatchers.supplement(listOf(source), listOf(target), translations) { statuses += it.status }
        assertEquals(listOf("ambiguous-client-matcher"), statuses)
        assertEquals(ids, target.tradeIds)
        assertEquals(1, target.matchers.size)
    }

    @Test
    fun `skips a text collision even with a different modifier type`() {
        val source = stat("Call", listOf(explicit), """{"string":"Grants Call of Steel","value":1}""")
        val target = stat("Call", listOf(explicit), """{"string":"赋予 # 级 钢铁呼唤"}""")
        val other = stat("Other", listOf("implicit.stat_123"),
            """{"string":"其他词缀","advanced":"赋予 ​钢铁呼唤"}""",
        )
        val statuses = mutableListOf<String>()
        TradeEffectMatchers.supplement(
            listOf(source), listOf(target, other),
            listOf(EffectMatcherTranslation("call_of_steel", "Grants Call of Steel", "赋予 钢铁呼唤")),
        ) { statuses += it.status }
        assertEquals(listOf("matcher-collision"), statuses)
        assertEquals(1, target.matchers.size)
    }

    @Test
    fun `does not derive a fixed value from a client template without placeholders`() {
        val source = stat("Call", listOf(explicit), """{"string":"Grants Level # Call of Steel"}""")
        val target = stat("Call", listOf(explicit), """{"string":"赋予 # 级 钢铁呼唤"}""")
        val statuses = mutableListOf<String>()
        TradeEffectMatchers.supplement(
            listOf(source), listOf(target),
            listOf(EffectMatcherTranslation("call_of_steel", "Grants Level # Call of Steel", "赋予 钢铁呼唤")),
        ) { statuses += it.status }
        assertEquals(listOf("unverified-matcher"), statuses)
        assertEquals(1, target.matchers.size)
    }
}
