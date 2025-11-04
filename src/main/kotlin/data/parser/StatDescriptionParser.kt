package data.parser

import Config
import java.io.File
import java.io.InputStreamReader

data class GameStatDescription(
    val fileName: String,
    val id: String,
    val enNames: List<String>,
    val cnNames: List<String>,
) {

    val uniqueId = "${fileName}_${id}"
}

// 预编译正则表达式以提高性能
private val ID_LINE_PATTERN = Regex("^\\d+\\s+(.+)$")
private val TEXT_LINE_PATTERN = Regex("^[^\"]*\"([^\"]*)\"")
private val LANG_PATTERN = Regex("^lang\\s+\"([^\"]+)\"")

/**
 * 解析器上下文，管理当前解析状态和数据
 */
class ParserContext {
    var currentId: String? = null
    var currentLanguage: String = "English"
    val enNames = mutableListOf<String>()
    val cnNames = mutableListOf<String>()

    /**
     * 为新的description块重置上下文
     */
    fun resetForNewBlock() {
        currentId = null
        currentLanguage = "English"
        enNames.clear()
        cnNames.clear()
    }

    /**
     * 生成当前块的GameStatDescription对象
     */
    fun createGameStatDescription(fileName: String): GameStatDescription? {
        return currentId?.let { id ->
            GameStatDescription(fileName, id, enNames.toList(), cnNames.toList())
        }
    }
}

private enum class State {
    WAITING_DESCRIPTION,
    PARSING_ID,
    PARSING_CONTENT,
}

/**
 * 使用状态机模式解析游戏本地化文件stat_descriptions.txt
 * 使用流式处理避免内存溢出，支持大型文件解析
 *
 * @return GameStatDescription对象的序列
 */
private fun doParseStatDescriptions(dir: File): Sequence<GameStatDescription> = sequence {
    dir.listFiles()?.forEach { file ->
        try {
            file.inputStream().use {
                val missingIds = mutableListOf<String?>()
                InputStreamReader(it, Charsets.UTF_16).useLines { lines ->
                    var expectedCount = 0

                    val iterator = object : Iterator<String> {
                        private val origin = lines.iterator()

                        // for debug
                        var line = 0

                        override fun hasNext(): Boolean = origin.hasNext()

                        override fun next(): String {
                            line++
                            val next = origin.next()
                            if (next.trim() == "description") {
                                expectedCount++
                            }
                            return next
                        }
                    }
                    val context = ParserContext()

                    var state = State.WAITING_DESCRIPTION
                    while (iterator.hasNext()) {
                        when (state) {
                            State.WAITING_DESCRIPTION -> {
                                while (iterator.hasNext() && iterator.next().trim() != "description") {
                                    // loop
                                }
                                state = State.PARSING_ID
                            }

                            State.PARSING_ID -> {
                                val idMatch = ID_LINE_PATTERN.find(iterator.next().trim())
                                val id = idMatch?.groupValues?.get(1)?.trim()
                                missingIds.add(id)
                                context.currentId = id
                                state = State.PARSING_CONTENT
                            }

                            State.PARSING_CONTENT -> {
                                val line = iterator.next().trim()
                                if (line.isEmpty()) {
                                    continue
                                }
                                val count = line.toIntOrNull()
                                if (count == null) {
                                    state = State.WAITING_DESCRIPTION
                                    continue
                                }
                                for (i in 0 until count) {
                                    val captured = TEXT_LINE_PATTERN.find(iterator.next().trim())!!.groupValues[1]
                                    val text = captured
                                        .unescape()
                                        .replace("{0:+d}", "#")
                                        .replace(Regex("\\{\\d?}"), "#")
                                        .replace(Regex("[{}]+"), "")
                                        .replace(Regex("<[^>]+>"), "")
                                    val names = when {
                                        context.currentLanguage == "English" -> context.enNames
                                        context.currentLanguage == "Simplified Chinese" -> context.cnNames
                                        // 国际服打了汉化补丁的时候, 取代的是韩文
                                        context.currentLanguage == "Korean" && Config.usePatchedIntlStatDescriptionFiles -> context.cnNames
                                        else -> null
                                    }
                                    names?.add(text)
                                }
                                val next = runCatching { iterator.next().trim() }.getOrNull()
                                if (next != null && next.matches(LANG_PATTERN)) {
                                    val nextLanguage = LANG_PATTERN.find(next)!!.groupValues[1]
                                    // 国服在同一条 stat_description 上偶尔会有两个一样的中文翻译, 例如:
                                    // 	lang "Simplified Chinese"
                                    //	1
                                    //		# "超然飞升" reminderstring ReminderTextPrismaticBulwark
                                    //	lang "Simplified Chinese"
                                    //	1
                                    //		# "超然飞升" reminderstring ReminderTextPrismaticBulwark
                                    // 会导致重复添加, 导致数量对不上, 所以在这里清空一下
                                    val names = when {
                                        nextLanguage == "English" -> context.enNames
                                        nextLanguage == "Simplified Chinese" -> context.cnNames
                                        // 国际服打了汉化补丁的时候, 取代的是韩文
                                        nextLanguage == "Korean" && Config.usePatchedIntlStatDescriptionFiles -> context.cnNames
                                        else -> null
                                    }
                                    names?.clear()
                                    context.currentLanguage = nextLanguage
                                    continue
                                }

                                context.createGameStatDescription(file.name)?.let {
                                    context.resetForNewBlock()
                                    missingIds.remove(it.id)
                                    expectedCount--
                                    yield(it)
                                }
                                state = when (next) {
                                    null -> return@useLines
                                    "description" -> State.PARSING_ID
                                    else -> State.WAITING_DESCRIPTION
                                }
                            }
                        }
                    }
                    if (expectedCount > 0) {
                        error("expected remains: $expectedCount")
                    }
                }
                if (missingIds.isNotEmpty()) {
                    error("missing ids: $missingIds")
                }
            }
        } catch (e: Throwable) {
            println("parsing file: $file")
            throw e
        }
    }
}

/**
 * 高效的字符串反转义函数
 */
private fun String.unescape(): String {
    val input = this
    val result = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        if (input[i] == '\\' && i + 1 < input.length) {
            when (input[i + 1]) {
                'n' -> result.append('\n')
                't' -> result.append('\t')
                'r' -> result.append('\r')
                '\\' -> result.append('\\')
                '"' -> result.append('"')
                else -> {
                    result.append(input[i])
                    result.append(input[i + 1])
                }
            }
            i += 2
        } else {
            result.append(input[i])
            i++
        }
    }
    return result.toString()
}

/**
 * A大国服功能补丁里的 stat_descriptions.txt 没有存英文信息, 导致无法对应上, 所以这里需要国际服的数据
 * TODO: 可选国际服 + 国服(A 大功能补丁 v1) or 国服
 */
fun parseAPatchedStatDescriptions(intlDescriptionsDir: File, tencentDescriptionsDir: File): List<GameStatDescription> {
    val enStatsFromGame = doParseStatDescriptions(intlDescriptionsDir)
    val cnStatsFromGameById = doParseStatDescriptions(tencentDescriptionsDir).associateBy { it.uniqueId }
    return buildList {
        enStatsFromGame.forEach { description ->
            val cnStat = cnStatsFromGameById[description.uniqueId]
            if (cnStat == null
                // 国际服中有些是已过期的, 会导致国服中没有对应字段, 所以这时候直接用国际服
                || cnStat.cnNames.isEmpty()
            ) {
                add(description)
            } else {
                if (description.enNames.size != cnStat.cnNames.size && cnStat.cnNames.size > 1) {
                    println("[WARNING] en names size: ${description.enNames.size}, cn names size: ${cnStat.cnNames.size} at: ${description.uniqueId}")
                    println(description.enNames)
                    println(cnStat.cnNames)
                }
                add(GameStatDescription(description.fileName, description.id, description.enNames, cnStat.cnNames))
            }
        }
    }
}

fun parseStatDescriptions(descriptionsDir: File): List<GameStatDescription> {
    return doParseStatDescriptions(descriptionsDir).onEach { description ->
        if (description.enNames.size != description.cnNames.size && description.cnNames.size > 1) {
//             error("Stat ${description.uniqueId} has different number of enNames and cnNames\n$description")
            println("[WARNING] en names size: ${description.enNames.size}, cn names size: ${description.cnNames.size} at: ${description.uniqueId}")
        }
    }.toList()
}
