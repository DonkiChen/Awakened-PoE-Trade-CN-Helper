package data.parser

import com.google.gson.annotations.SerializedName
import util.fromJson
import java.io.File

/**
 * 解析从游戏中导出的数据
 *
 * @return key: item 英文名称, value: item 中文名称
 */
inline fun <reified T : BaseTableItem> parseExportedTableDataToMapper(
    exportedDataDir: File,
    gameBaseDir: String,
    gameFileName: String,
    cnLang: String,
    predicate: (T) -> Boolean = { true }
): Map<T, T> {
    val result = mutableMapOf<T, T>()
    val enFile = File(exportedDataDir, "/${gameBaseDir}/tables/English/${gameFileName}")
    val cnFile = File(exportedDataDir, "/${gameBaseDir}/tables/${cnLang}/${gameFileName}")

    val enItems = fromJson<List<T>>(enFile.reader()).filter(predicate).associateBy { it.id }
    val cnItems = fromJson<List<T>>(cnFile.reader()).filter(predicate).associateBy { it.id }

    enItems.forEach { (id, enItem) ->
        result[enItem] = cnItems[id] ?: return@forEach
    }
    return result
}

open class BaseTableItem(
    @SerializedName(alternate = ["Id", "_index"], value = "id")
    val id: String,
    @SerializedName(alternate = ["DisplayedName", "ShortName", "Text2"], value = "Name")
    val name: String
)

// key: id, value: (lang, name)