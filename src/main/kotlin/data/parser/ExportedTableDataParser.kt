package data.parser

import Config
import com.google.gson.annotations.SerializedName
import util.fromJson
import java.io.File

/**
 * 解析从游戏中导出的数据
 *
 * @return key: item 英文名称, value: item 中文名称
 */
inline fun <reified T : BaseTableItem> parseExportedTableData(
    exportedDataDir: File,
    gameFileName: String,
    predicate: (T) -> Boolean = { true }
): Map<String, String> {
    val result = mutableMapOf<String, String>()
    // TODO: 目标文件夹可配置
    val which = if (Config.usePatchedIntlStatDescriptionFiles) "intl" else "tencent"
    val enFile = File(exportedDataDir, "/${which}/tables/English/${gameFileName}")
    val cnFile = File(exportedDataDir, "/${which}/tables/Simplified Chinese/${gameFileName}")

    val enItems = fromJson<List<T>>(enFile.reader()).filter(predicate).associateBy { it.id }
    val cnItems = fromJson<List<T>>(cnFile.reader()).filter(predicate).associateBy { it.id }

    enItems.forEach { (id, enItem) ->
        result[enItem.name] = cnItems[id]?.name ?: return@forEach
    }
    return result
}

open class BaseTableItem(
    @SerializedName(alternate = ["Id", "_index"], value = "id")
    val id: String,
    @SerializedName(alternate = ["DisplayedName", "ShortName", "Text2"], value = "Name")
    val name: String
)