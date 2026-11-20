package data.parser

import java.io.BufferedReader
import java.io.File

data class CsdLocation(val file: String, val line: Int) {
    override fun toString(): String = "$file:$line"
}

data class StatCondition(val token: String) {
    private val negated get() = token.startsWith('!')
    internal val bounds: IntRange?
        get() {
            val condition = token.removePrefix("!")
            if (condition == "#") return Int.MIN_VALUE..Int.MAX_VALUE
            val parts = condition.split('|', limit = 2)
            if (parts.size == 1) return parts[0].toIntOrNull()?.let { it..it }
            val min = if (parts[0] == "#") Int.MIN_VALUE else parts[0].toIntOrNull() ?: return null
            val max = if (parts[1] == "#") Int.MAX_VALUE else parts[1].toIntOrNull() ?: return null
            return (min..max).takeUnless { it.isEmpty() }
        }

    internal fun contains(value: Int): Boolean = bounds?.let { (value in it) != negated } ?: false

    internal fun intersect(range: IntRange): List<IntRange> {
        val bounds = bounds ?: return emptyList()
        if (!negated) return listOf(maxOf(bounds.first, range.first)..minOf(bounds.last, range.last))
            .filterNot { it.isEmpty() }
        return buildList {
            if (range.first < bounds.first) add(range.first..minOf(range.last, bounds.first - 1))
            if (range.last > bounds.last) add(maxOf(range.first, bounds.last + 1)..range.last)
        }
    }
}

data class StatDescriptionRule(
    val conditions: List<StatCondition>,
    val restrictions: List<String>,
    val template: String,
    // Keep the suffix lossless at token level. The registry, not the parser, knows instruction arities.
    val directiveTokens: List<String>,
    val location: CsdLocation,
)

data class GameStatDescription(
    val fileName: String,
    val statIds: List<String>,
    val rulesByLang: Map<String, List<StatDescriptionRule>>,
    val location: CsdLocation,
    val identifier: String?,
) {
    val id = statIds.joinToString(" ")
    val uniqueId = "${fileName}_${id}"
}

private val whitespace = Regex("\\s+")
private val languageHeader = Regex("lang\\s+\"([^\"]+)\"")
private fun String.tokens() = if (isBlank()) emptyList() else trim().split(whitespace)

object StatDescriptionParsers {
    /**
     * [languages] limits the rules retained in memory, not the language blocks read.
     * Includes are not followed: the exporter already supplies individual files, identified by filename.
     */
    fun parse(
        descriptionsDir: File,
        unlabelledLanguage: String = "English",
        languages: Set<String>? = null,
    ): List<GameStatDescription> = buildList {
        descriptionsDir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("csd", true) || it.extension.equals("txt", true)) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                file.bufferedReader(Charsets.UTF_16).use { reader ->
                    addAll(parseFile(CsdReader(file, reader), unlabelledLanguage, languages))
                }
            }
    }

    private fun parseFile(input: CsdReader, defaultLanguage: String, languages: Set<String>?) = sequence {
        var line = input.next()
        while (line != null) {
            val header = line.tokens()
            if (header.firstOrNull() != "description") {
                line = input.next()
                continue
            }
            val location = input.location
            require(header.size <= 2) { "$location: invalid description header: $line" }
            val ids = input.required("stat IDs").tokens()
            val count = ids.firstOrNull()?.toIntOrNull()
            require(count != null && count > 0 && count == ids.size - 1) {
                "${input.location}: invalid stat ID count: $ids"
            }
            val rulesByLang = linkedMapOf<String, List<StatDescriptionRule>>()
            var language = defaultLanguage
            do {
                val rowCount = input.required("row count").toIntOrNull()
                require(rowCount != null && rowCount >= 0) { "${input.location}: invalid row count" }
                val keep = languages == null || language in languages
                val rules = if (keep) ArrayList<StatDescriptionRule>(rowCount) else null
                repeat(rowCount) {
                    val row = input.required("rule for $language")
                    if (rules != null) rules.add(parseRow(row, count, input.location))
                }
                // Some Tencent extracts repeat a language section. Keep the last one, as before.
                if (rules != null) rulesByLang[language] = rules
                line = input.next()
                val lang = line?.let(languageHeader::matchEntire) ?: break
                language = lang.groupValues[1]
            } while (true)
            yield(GameStatDescription(input.file.nameWithoutExtension, ids.drop(1), rulesByLang, location, header.getOrNull(1)))
        }
    }

    private fun parseRow(row: String, statCount: Int, location: CsdLocation): StatDescriptionRule {
        val start = row.indexOf('"')
        require(start >= 0) { "$location: missing quoted template: $row" }
        var end = start + 1
        while (end < row.length && row[end] != '"') {
            end += if (row[end] == '\\') 2 else 1
        }
        require(end < row.length) { "$location: unterminated template: $row" }
        val prefix = row.substring(0, start).tokens()
        require(prefix.size >= statCount) { "$location: expected $statCount stat conditions: $row" }
        return StatDescriptionRule(
            prefix.take(statCount).map(::StatCondition),
            prefix.drop(statCount),
            row.substring(start + 1, end),
            row.substring(end + 1).tokens(),
            location,
        )
    }

    private class CsdReader(val file: File, private val reader: BufferedReader) {
        private var lineNumber = 0
        val location get() = CsdLocation(file.path, lineNumber)

        fun next(): String? {
            while (true) {
                val line = reader.readLine() ?: return null
                lineNumber++
                if (line.isNotBlank()) return line.trim()
            }
        }

        fun required(field: String): String = next() ?: error("$location: expected $field before end of file")
    }
}
