import data.GameDataRepo
import item.ItemPatcher
import stat.StatPatcher
import java.io.File

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        // 每个 mapper 对应一个目标数据集。国际服将简体中文文案标记为 "Traditional Chinese"。
        GameDataRepo.prepareMapper(
            sourceExportDirName = "intl_poedb",
            targetExportDirName = "intl_poedb",
            targetLanguageKey = "Traditional Chinese",
            sourceStatUnlabelledLanguage = "English",
            targetStatUnlabelledLanguage = "English"
        )
        GameDataRepo.prepareMapper(
            sourceExportDirName = "intl_amsco2",
            targetExportDirName = "intl_amsco2",
            targetLanguageKey = "Traditional Chinese",
            sourceStatUnlabelledLanguage = "English",
            targetStatUnlabelledLanguage = "English"
        )

        GameDataRepo.prepareMapper(
            sourceExportDirName = "tencent",
            targetExportDirName = "tencent",
            targetLanguageKey = "Simplified Chinese",
            sourceStatUnlabelledLanguage = "English",
            // tencent 文件以未标记的英文内容开头，后续内容明确标记为简体中文。
            targetStatUnlabelledLanguage = "English"
        )

        // tencent_amsco2 的 stat 描述文件只有中文。使用 intl_amsco2 中的英文描述，
        // 并仅对这个 mapper 将无语言标记的目标内容视为简体中文。
        GameDataRepo.prepareMapper(
            sourceExportDirName = "intl_amsco2",
            targetExportDirName = "tencent_amsco2",
            targetLanguageKey = "Simplified Chinese",
            sourceStatUnlabelledLanguage = "English",
            targetStatUnlabelledLanguage = "Simplified Chinese"
        )

        ItemPatcher.patch(GameDataRepo.mappers)
        StatPatcher.patch(GameDataRepo.mappers)
    }
}
