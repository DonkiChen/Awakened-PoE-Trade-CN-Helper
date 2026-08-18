package data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import util.NdJsonHelper
import java.io.File

object AptDataRepo {
    const val APT_PROJECT_DIR = """../"""

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
            stats.map { it.refName }
                .toSet()
                .toString()
        }

        fun deepClone(): StatGroup {
            return StatGroup(
                stats.map { it.deepClone() },
                rawData.deepCopy()
            )
        }

        fun syncToRawData() {
            rawData.remove("stats")
            val newStats = JsonArray(this.stats.size)
            this.stats.forEach { newStats.add(it.rawData) }
            rawData.add("stats", newStats)
        }
    }

    data class Stat(
        val refName: String,
        override val rawData: JsonObject,
    ) : BaseStat {
        override val associateKey: String
            get() = refName

        val matchers: List<Matcher>
            get() = rawData.getAsJsonArray("matchers")
                .map {
                    Matcher(it.asJsonObject["string"].asString, it.asJsonObject["advanced"]?.asString, it.asJsonObject)
                }

        fun deepClone(): Stat {
            return Stat(refName, rawData.deepCopy())
        }

        fun addMatcher(matcher: Matcher) {
            rawData.getAsJsonArray("matchers")
                .add(matcher.rawData)
        }

        fun replaceMatchers(matchers: List<JsonObject>) {
            val newMatchers = JsonArray(matchers.size)
            matchers.forEach { newMatchers.add(it) }
            rawData.add("matchers", newMatchers)
        }

        fun removeMatcher(matcher: Matcher) {
            rawData.getAsJsonArray("matchers")
                .remove(matcher.rawData)
        }

        fun hasMatchers(): Boolean {
            return rawData.getAsJsonArray("matchers").size() > 0
        }

        data class Matcher(
            val string: String,
            val advanced: String?,
            val rawData: JsonObject,
        ) {
            val negate: Boolean
                get() = rawData["negate"]?.asBoolean ?: false

            fun updateString(newString: String) {
                rawData.addProperty("string", newString)
            }

            fun updateAdvancedIfExists(newAdvanced: String?) {
                if (!newAdvanced.isNullOrBlank() && rawData.has("advanced")) {
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
        fun updateName(name: String): Item {
            rawData.addProperty("name", name)
            return this
        }
    }

    private fun List<JsonObject>.toItems(): List<Item> {
        return map { Item(it["refName"].asString, it["name"].asString, it) }
    }

    val enItems by lazy {
        read(EN_ITEMS_FILE).toItems()
    }
}
