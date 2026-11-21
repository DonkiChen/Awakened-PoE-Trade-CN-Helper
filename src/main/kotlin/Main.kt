import data.GameDataRepo
import item.ItemPatcher
import stat.ModNamePatcher
import stat.StatPatcher
import java.io.File

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.singleOrNull()
        require(args.isEmpty() || mode in setOf("--mod-names-only", "--check-mod-names", "--audit-stats")) {
            "Usage: generateTranslations [--args=--mod-names-only | --args=--check-mod-names | --args=--audit-stats]"
        }
        // 每个 mapper 对应一个目标数据集。国际服将简体中文文案标记为 "Traditional Chinese"。
        GameDataRepo.prepareMapper(
            id = "intl_poedb_patched",
            sourceExportDirName = "intl_poedb",
            targetExportDirName = "intl_poedb",
            targetLanguageKey = "Traditional Chinese",
            sourceStatUnlabelledLanguage = "English",
            targetStatUnlabelledLanguage = "English"
        )
        GameDataRepo.prepareMapper(
            id = "intl_amsco2_patched",
            sourceExportDirName = "intl_amsco2",
            targetExportDirName = "intl_amsco2",
            targetLanguageKey = "Traditional Chinese",
            sourceStatUnlabelledLanguage = "English",
            targetStatUnlabelledLanguage = "English"
        )

        GameDataRepo.prepareMapper(
            id = "tencent_origin",
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
            id = "tencent_amsco_patched",
            sourceExportDirName = "intl_amsco2",
            targetExportDirName = "tencent_amsco2",
            targetLanguageKey = "Simplified Chinese",
            sourceStatUnlabelledLanguage = "English",
            targetStatUnlabelledLanguage = "Simplified Chinese"
        )

        if (mode == "--audit-stats") {
            StatPatcher.audit(GameDataRepo.mappers)
            return
        }
        ModNamePatcher.patch(GameDataRepo.mappers, checkOnly = mode == "--check-mod-names")
        if (mode == null) {
            ItemPatcher.patch(GameDataRepo.mappers)
            StatPatcher.patch(GameDataRepo.mappers)
        }
    }
}
