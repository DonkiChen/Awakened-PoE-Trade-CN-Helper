package data.parser

import com.google.gson.annotations.SerializedName
import data.GameDataRepo
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
    EXPEDITION_AREA,
    DEFAULT
}

data class Text(
    val string: String,
    val advanced: String? = null,
)

data class ExtraStat private constructor(
    val refName: String,
    val type: Type,
    @SerializedName("resolver")
    private val _resolver: Resolver?,
    val en: List<Text>,
    @SerializedName("cn")
    val cn: List<Text>?,
    @SerializedName("cns")
    val cns: List<List<Text>>?,
) {
    val resolver: Resolver
        get() = _resolver ?: Resolver.FLATTEN

    /** Old `cn` entries are one translation set; `cns` contains multiple sets. */
    val translationSets: List<List<Text>>
        get() = cns?.takeIf { it.isNotEmpty() } ?: listOfNotNull(cn)
}

private fun String.safeFormat(vararg args: String): String {
    return runCatching { format(*args) }.getOrDefault(this)
}

private data class FormatArgs(
    val refName: String,
    val string: String = refName,
    val advanced: String = string,
)

private fun Map<String, String>.toArgs(): Map<FormatArgs, FormatArgs> {
    return map { FormatArgs(it.key) to FormatArgs(it.value) }.toMap()
}

private fun resolvePlaceholder(stat: ExtraStat, items: Map<FormatArgs, FormatArgs>): List<ExtraStat> {
    return when (stat.resolver) {
        Resolver.FLATTEN -> {
            items.map { (enName, cnName) ->
                stat.copy(
                    refName = stat.refName.safeFormat(enName.refName),
                    en = stat.en.map { text ->
                        text.copy(
                            string = text.string.safeFormat(enName.string),
                            advanced = text.advanced?.safeFormat(enName.advanced)
                        )
                    },
                    cn = stat.cn?.map { text ->
                        text.copy(
                            string = text.string.safeFormat(cnName.string),
                            advanced = text.advanced?.safeFormat(cnName.advanced)
                        )
                    },
                    cns = stat.cns?.map { texts ->
                        texts.map { text ->
                            text.copy(
                                string = text.string.safeFormat(cnName.string),
                                advanced = text.advanced?.safeFormat(cnName.advanced)
                            )
                        }
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
                                string = text.string.safeFormat(enName.string),
                                advanced = text.advanced?.safeFormat(enName.advanced)
                            )
                        }
                    },
                    cn = stat.cn?.flatMap { text ->
                        items.values.map { cnName ->
                            text.copy(
                                string = text.string.safeFormat(cnName.string),
                                advanced = text.advanced?.safeFormat(cnName.advanced)
                            )
                        }
                    },
                    cns = stat.cns?.map { texts ->
                        texts.flatMap { text ->
                            items.values.map { cnName ->
                                text.copy(
                                    string = text.string.safeFormat(cnName.string),
                                    advanced = text.advanced?.safeFormat(cnName.advanced)
                                )
                            }
                        }
                    }
                )
            )

        }
    }
}

fun parseExtraStats(mapper: GameDataRepo.GameDataMapper, file: File): List<ExtraStat> {
    val result = mutableListOf<ExtraStat>()
    file.reader()
        .use { util.JsonUtils.fromJson<List<ExtraStat>>(it) }
        .forEach { stat ->
            val map = when (stat.type) {
                Type.DEFAULT -> {
                    result.add(stat)
                    return@forEach
                }

                Type.EXPEDITION_AREA -> mapper.expeditionAreas.toArgs()
            }
            result.addAll(resolvePlaceholder(stat, map))
        }
    return result
}
