import item.ItemPatcher
import stat.StatPatcher

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        ItemPatcher.patch()
        StatPatcher.patch()
    }
}