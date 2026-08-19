package util

// [+<chaos>{升}]恐惧之尖啸精华[召唤L6] => [+升]恐惧之尖啸精华[召唤L6]
private val markupRegex = Regex("<[^>]+>\\{([^}]+)}")

fun String.removeMarkup(): String {
    return markupRegex.replace(this) { it.groupValues[1] }
}
