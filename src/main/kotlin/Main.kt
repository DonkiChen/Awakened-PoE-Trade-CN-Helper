import data.GameDataRepo
import item.ItemPatcher
import stat.StatPatcher
import java.io.File

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        GameDataRepo.prepareMapper(
            sourceBaseDirName = "intl_amsco2",
            targetBaseDir = "intl_amsco2",
            targetLang = "Traditional Chinese",
            targetStatDefaultLang = "English"
        )
        GameDataRepo.prepareMapper(
            sourceBaseDirName = "intl_poedb",
            targetBaseDir = "intl_poedb",
            targetLang = "Traditional Chinese",
            targetStatDefaultLang = "English"
        )
        ItemPatcher.patch(GameDataRepo.mappers)
        StatPatcher.patch(GameDataRepo.mappers)
    }
}