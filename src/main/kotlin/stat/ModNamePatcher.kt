package stat

import data.AptDataRepo
import data.GameDataRepo.GameDataMapper
import data.parser.ModNameRow
import util.fromJson
import util.gson
import util.removeMarkup
import java.io.File

object ModNamePatcher {
    private val dataDir = File(AptDataRepo.APT_PROJECT_DIR, "renderer/public/data")
    private val groupPrefix = Regex("""^  ([A-Z_]+_MODS):[ \t]*""", RegexOption.MULTILINE)
    private const val BEGIN = "  // BEGIN GENERATED MOD NAMES"
    private const val END = "  // END GENERATED MOD NAMES"

    internal fun readGroups(clientStrings: String): Map<String, List<String>> {
        return groupPrefix.findAll(clientStrings).associate { match ->
            // Each *_MODS property is a one-line array of single- or double-quoted literals.
            val literal = clientStrings.substring(match.range.last + 1)
                .substringBefore('\n').trimEnd().removeSuffix(",")
            match.groupValues[1] to fromJson<List<String>>(literal)
        }.also { check(it.isNotEmpty()) { "No *_MODS groups found in en/client_strings.js" } }
    }

    internal fun collectNames(
        groups: Map<String, List<String>>,
        english: List<ModNameRow>,
        localized: List<ModNameRow>,
        warn: (String) -> Unit,
    ): Map<String, Set<String>> {
        val idsByName = english.groupBy({ it.name }, { it.id })
        val localizedById = localized.groupBy({ it.id }, { it.name })

        return groups.mapValues { (group, names) ->
            buildSet {
                for (name in names) {
                    val ids = idsByName[name]
                    if (ids == null) {
                        warn("$group: no English entry for \"$name\"")
                        continue
                    }
                    val missing = mutableSetOf<String>()
                    for (id in ids) {
                        val translations = localizedById[id].orEmpty().mapNotNull {
                            it?.removeMarkup()?.trim()?.takeIf(String::isNotEmpty)
                        }
                        if (translations.isEmpty()) missing.add(id)
                        addAll(translations)
                    }
                    if (missing.isNotEmpty()) {
                        warn("$group: \"$name\" missing ${missing.size} localized ID(s): ${missing.take(5).joinToString()}")
                    }
                }
            }
        }
    }

    internal fun replaceGeneratedBlock(current: String, groups: Map<String, Set<String>>): String {
        val newline = if (current.contains("\r\n")) "\r\n" else "\n"
        val lines = current.split(newline)
        val first = lines.indexOf(BEGIN)
        val last = lines.indexOf(END)
        check(first >= 0 && last > first) { "Missing generated block markers in zh_CN/client_strings.js" }

        val generated = groups.map { (key, names) ->
            "  $key: ${names.sorted().joinToString(prefix = "[", postfix = "]") { gson.toJson(it) }},"
        }
        return (lines.take(first + 1) + generated + lines.drop(last)).joinToString(newline)
    }

    fun patch(mappers: List<GameDataMapper>, checkOnly: Boolean = false) {
        val englishGroups = readGroups(File(dataDir, "en/client_strings.js").readText())
        val result = englishGroups.mapValues { mutableSetOf<String>() }
        for (mapper in mappers) {
            val (english, localized) = mapper.readModNameTables()
            collectNames(englishGroups, english, localized) { System.err.println("[${mapper.id}] $it") }
                .forEach { (key, names) -> result.getValue(key).addAll(names) }
        }

        val owners = mutableMapOf<String, String>()
        for ((key, names) in result) {
            check(names.isNotEmpty()) { "$key: no localized names generated" }
            for (name in names) {
                val previous = owners.putIfAbsent(name, key)
                if (previous != null) System.err.println("\"$name\" is in both $previous and $key")
            }
        }

        val output = File(dataDir, "zh_CN/client_strings.js")
        val current = output.readText()
        val generated = replaceGeneratedBlock(current, result)
        if (generated == current) {
            println("Simplified Chinese mod names are up to date.")
        } else {
            check(!checkOnly) { "Generated mod names are out of date. Run generateTranslations --args=--mod-names-only." }
            output.writeText(generated)
            println("Updated the generated mod names in zh_CN/client_strings.js.")
        }
    }
}
