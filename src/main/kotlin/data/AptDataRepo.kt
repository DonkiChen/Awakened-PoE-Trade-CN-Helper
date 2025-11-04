package data

import com.google.gson.JsonObject
import util.NdJsonHelper
import java.io.File

object AptDataRepo {
    const val APT_PROJECT_DIR = """../Awakened-PoE-Trade-Simplified-Chinese"""

    private const val EN_ITEMS_FILE = """renderer/public/data/en/items.ndjson"""
    private const val EN_STATS_FILE = """renderer/public/data/en/stats.ndjson"""

    private fun read(file: String): List<JsonObject> {
        val itemFile = File(APT_PROJECT_DIR, file)
        return NdJsonHelper.readNdJson(itemFile)
    }

    sealed interface BaseStat {
        val rawData: JsonObject
        val associateKey: String
    }

    data class StatGroup(
        val stats: List<Stat>,
        override val rawData: JsonObject,
    ) : BaseStat {
        override val associateKey by lazy {
            stats.map { it.refName }.toSet().toString()
        }
    }

    data class Stat(
        val refName: String,
        override val rawData: JsonObject,
    ) : BaseStat {
        override val associateKey: String
            get() = refName

        val matchers by lazy {
            rawData.getAsJsonArray("matchers").map { Matcher(it.asJsonObject["string"].asString, it.asJsonObject) }
        }

        data class Matcher(
            val string: String,
            val rawData: JsonObject,
        ) {
            fun updateString(newString: String) {
                rawData.addProperty("string", newString)
            }

            fun updateAdvancedIfExists(newAdvanced: String?) {
                if (newAdvanced != null && rawData.has("advanced")) {
                    rawData.addProperty("advanced", newAdvanced)
                }
            }
        }
    }

    private fun List<JsonObject>.toStatOrGroup(): List<BaseStat> {
        return this.map {
            val ref = it["ref"]?.asString
            return@map if (ref == null) {
                StatGroup(
                    stats = it["stats"].asJsonArray.map { stat ->
                        Stat(stat.asJsonObject["ref"].asString, stat.asJsonObject)
                    },
                    rawData = it
                )
            } else {
                Stat(ref, it)
            }
        }
    }

    val enStatOrGroup by lazy {
        read(EN_STATS_FILE).toStatOrGroup()
    }

    data class Item(
        val refName: String,
        val name: String,
        val rawData: JsonObject
    ) {
        fun updateName(name: String) {
            rawData.addProperty("name", name)
        }
    }

    private fun List<JsonObject>.toItems(): List<Item> {
        return map { Item(it["refName"].asString, it["name"].asString, it) }
    }

    val enItems by lazy {
        read(EN_ITEMS_FILE).toItems()
    }
}