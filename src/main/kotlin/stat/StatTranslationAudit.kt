package stat

import com.google.gson.Gson
import com.google.gson.JsonObject
import data.GameDataRepo
import util.fromJson
import java.io.File
import java.io.Writer

internal class StatTranslationAudit(
    private val writer: Writer,
    mappers: List<GameDataRepo.GameDataMapper>,
) {
    data class Location(val inputLine: Int, val groupIndex: Int? = null)
    data class LegacyOrigin(val field: String, val localizedText: String, val origin: TradeCsdOrigin)

    private data class TradeSnapshot(val result: List<TradeGroup>)
    private data class TradeGroup(val entries: List<TradeEntry>)
    private data class TradeEntry(val id: String)

    private val gson = Gson()
    private val englishTrade = readSnapshot("en")
    private val chineseTrade = readSnapshot("cn")
    private val decisions = sortedMapOf<String, Int>()
    private val lookup = sortedMapOf<String, Int>()
    private val changes = sortedMapOf<String, Int>()
    private var missing = 0
    private val seenIds = mutableSetOf<String>()
    private val metadataFields = setOf("string", "advanced")
    private var statMetadataChanges = 0

    init {
        val exports = mappers.map { it.auditExports() }
        require(exports.all { it["sourceCsdFiles"] != 0 && it["targetCsdFiles"] != 0 }) {
            "CSD exports are missing: $exports. Run scripts/build_and_export.bat before auditing."
        }
        write(mapOf("kind" to "inputs", "exports" to exports))
    }

    private fun readSnapshot(locale: String): Set<String>? {
        val file = File("trade_data/$locale/stats.json")
        return file.takeIf(File::isFile)?.reader(Charsets.UTF_8)?.use {
            fromJson<TradeSnapshot>(it).result.flatMap { group -> group.entries.map { entry -> entry.id } }.toSet()
        }
    }

    private fun write(value: Any) {
        // Gradle can recode application stdout on Windows. ASCII JSON escapes keep
        // redirected reports portable and lossless even with a non-UTF-8 code page.
        for (char in gson.toJson(value)) {
            if (char.code > 0x7f) writer.append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else writer.append(char)
        }
        writer.append('\n')
    }

    private fun MutableMap<String, Int>.count(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    fun stat(input: JsonObject, output: JsonObject) {
        val translatable = setOf("matchers", "reminderText")
        if (input.entrySet().filterNot { it.key in translatable } != output.entrySet().filterNot { it.key in translatable }) {
            statMetadataChanges++
        }
    }

    fun matcher(
        location: Location,
        index: Int,
        ref: String,
        ids: List<String>,
        input: JsonObject,
        resolution: TradeStatResolver.Resolution,
        legacy: List<JsonObject>,
        output: List<JsonObject>,
        refScopedAliases: List<JsonObject>,
        legacyMappers: List<String>,
        legacyCsdOrigins: List<LegacyOrigin>,
    ) {
        lookup.count(resolution.status)
        seenIds.addAll(ids)
        val removed = legacy.distinct() - output.toSet()
        val added = output.distinct() - legacy.toSet()
        val hasTranslation = output.any { it != input }
        val decision = when {
            resolution.accepted.isNotEmpty() -> resolution.status
            resolution.status == "conflict" -> if (hasTranslation) "conflict-verified-legacy" else "conflict"
            !hasTranslation -> "missing"
            resolution.status == "special" -> "special-legacy"
            else -> "legacy-fallback"
        }
        decisions.count(decision)
        if (!hasTranslation) missing++
        if (removed.isNotEmpty() || added.isNotEmpty()) changes.count("changedMatchers")
        if (removed.isNotEmpty()) {
            changes.count("matchersWithRemovedVariants")
            changes["removedVariants"] = (changes["removedVariants"] ?: 0) + removed.size
        }
        if (added.isNotEmpty()) changes.count("matchersWithAddedVariants")
        if (legacy.firstOrNull() != output.firstOrNull()) changes.count("changedFirstVariant")
        if (legacy.any { it != input } && !hasTranslation) changes.count("newlyUntranslatedMatchers")
        fun metadata(json: JsonObject) = json.entrySet().filterNot { it.key in metadataFields }.associate { it.key to it.value }
        val metadataChanged = output.any { metadata(it) != metadata(input) }
        if (metadataChanged) changes.count("changedMatcherMetadata")
        val verifiedStatIds = resolution.matches.flatMap { it.origins.map(TradeCsdOrigin::statIds) }.toSet()
        val removedDetails = removed.map { variant ->
            val origins = legacyCsdOrigins.filter { variant[it.field]?.asString == it.localizedText }
            val reason = when {
                origins.any { it.origin.statIds !in verifiedStatIds } &&
                    origins.none { it.origin.statIds in verifiedStatIds } -> "different-internal-stat"
                resolution.status == "conflict" -> "not-verified-in-conflict"
                else -> "not-verified-by-id"
            }
            changes.count("removed:$reason")
            mapOf("variant" to variant, "reason" to reason, "legacyCsdOrigins" to origins)
        }

        val snapshotMissing = ids.filter { id ->
            (englishTrade != null && id !in englishTrade) || (chineseTrade != null && id !in chineseTrade)
        }
        write(mapOf(
            "kind" to "matcher",
            "location" to location,
            "matcherIndex" to index,
            "localized" to hasTranslation,
            "ref" to ref,
            "tradeIds" to ids,
            "tradeSnapshotMissingIds" to snapshotMissing.takeIf { it.isNotEmpty() }?.map { id ->
                mapOf("id" to id, "inEnglish" to englishTrade?.contains(id), "inChinese" to chineseTrade?.contains(id))
            },
            "decision" to decision,
            "lookup" to resolution.status,
            "source" to input,
            "selected" to resolution.accepted.takeIf { it.isNotEmpty() },
            "alternatives" to resolution.matches.takeIf { resolution.status == "conflict" },
            "candidates" to resolution.candidates.takeIf { resolution.accepted.isEmpty() && it.isNotEmpty() },
            "legacyMappers" to legacyMappers,
            "refScopedAliases" to refScopedAliases.takeIf { it.isNotEmpty() },
            "legacyCsdOrigins" to legacyCsdOrigins.takeIf { it.isNotEmpty() },
            "legacy" to legacy.distinct(),
            "output" to output.distinct(),
            "removed" to removed,
            "removedDetails" to removedDetails.takeIf { it.isNotEmpty() },
            "added" to added,
            "metadataChanged" to metadataChanged,
        ))
    }

    fun finish(outputRows: Int) {
        val counts = mapOf(
            "distinctTradeIds" to seenIds.size,
            "inEnglish" to englishTrade?.let { en -> seenIds.count { it in en } },
            "inChinese" to chineseTrade?.let { cn -> seenIds.count { it in cn } },
            "inBoth" to englishTrade?.let { en -> chineseTrade?.let { cn -> seenIds.count { it in en && it in cn } } },
        )
        write(mapOf(
            "kind" to "summary",
            "matchers" to lookup.values.sum(),
            "outputRows" to outputRows,
            "untranslatedMatchers" to missing,
            "decisions" to decisions,
            "lookup" to lookup,
            "differences" to (mapOf(
                "newlyUntranslatedMatchers" to 0,
                "changedMatcherMetadata" to 0,
                "changedStatsMetadata" to statMetadataChanges,
                "matchersWithAddedVariants" to 0,
            ) + changes),
            "tradeSnapshotCheck" to counts,
        ))
    }
}
