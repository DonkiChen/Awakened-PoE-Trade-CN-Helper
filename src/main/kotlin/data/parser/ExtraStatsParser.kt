package data.parser

import com.google.gson.annotations.SerializedName
import data.GameDataRepo
import util.fromJson
import java.io.File

enum class Resolver {
    /**
     * 将数据展开, [ExtraStat.en] 中的 n 数据, 会生成 n 个 [ExtraStat], 对应 n 行 stats.ndjson 中的数据,
     * 例如: While a Pinnacle Atlas Boss is in your Presence,
     */
    FLATTEN,

    /**
     * 将数据合并, 只会有 1 个 [ExtraStat], 对应 1 行 stats.ndjson 中的数据, 其中会有多个 matchers,
     * 例如: Allocates # if you have the matching modifier on Forbidden Flesh
     */
    MERGE,
}

enum class Type {
    ACTIVE_SKILL,
    PASSIVE_SKILL,
    EXPEDITION_AREA,
    KALANDRA_TILE,
    BETRAYAL_NPC,
    ASCENDANCY,
    KEYSTONE,
    EXARCH_EATER,
    DEFAULT
}

data class Text(
    val string: String,
    val advanced: String?,
)

data class ExtraStat private constructor(
    val refName: String,
    val type: Type,
    @SerializedName("resolver")
    private val _resolver: Resolver?,
    val en: List<Text>,
    val cn: List<Text>
) {
    val resolver: Resolver
        get() = _resolver ?: Resolver.FLATTEN
}

private fun String.safeFormat(vararg args: Any): String {
    return runCatching { format(*args) }.getOrDefault(this)
}

private fun resolvePlaceholder(stat: ExtraStat, items: Map<String, String>): List<ExtraStat> {
    return when (stat.resolver) {
        Resolver.FLATTEN -> {
            items.map { (enName, cnName) ->
                stat.copy(
                    refName = stat.refName.safeFormat(enName),
                    en = stat.en.map { text ->
                        text.copy(
                            string = text.string.safeFormat(enName),
                            advanced = text.advanced?.safeFormat(enName)
                        )
                    },
                    cn = stat.cn.map { text ->
                        text.copy(
                            string = text.string.safeFormat(cnName),
                            advanced = text.advanced?.safeFormat(cnName)
                        )
                    }
                )
            }
        }

        Resolver.MERGE -> {
            listOf(
                stat.copy(
                    refName = stat.refName,
                    en = stat.en.flatMap { text ->
                        items.keys.map { enName ->
                            text.copy(
                                string = text.string.safeFormat(enName),
                                advanced = text.advanced?.safeFormat(enName)
                            )
                        }
                    },
                    cn = stat.cn.flatMap { text ->
                        items.values.map { cnName ->
                            text.copy(
                                string = text.string.safeFormat(cnName),
                                advanced = text.advanced?.safeFormat(cnName)
                            )
                        }
                    }
                )
            )

        }
    }
}

fun parseExtraStats(file: File): List<ExtraStat> {
    val result = mutableListOf<ExtraStat>()
    file.reader().use { fromJson<List<ExtraStat>>(it) }
        .forEach { stat ->
            val map = when (stat.type) {
                Type.DEFAULT -> {
                    result.add(stat)
                    return@forEach
                }

                Type.ACTIVE_SKILL -> GameDataRepo.activeSkills
                Type.PASSIVE_SKILL -> GameDataRepo.passiveSkills
                Type.EXPEDITION_AREA -> GameDataRepo.expeditionAreas
                Type.KALANDRA_TILE -> GameDataRepo.kalandraTiles
                Type.BETRAYAL_NPC -> GameDataRepo.betrayalNpcs
                Type.ASCENDANCY -> GameDataRepo.ascendancies
                Type.KEYSTONE -> GameDataRepo.keystones
                Type.EXARCH_EATER -> GameDataRepo.exarchEaterMods
            }
            result.addAll(resolvePlaceholder(stat, map))
        }
    return result
}