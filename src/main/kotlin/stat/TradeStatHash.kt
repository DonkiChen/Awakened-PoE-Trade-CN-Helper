package stat

internal object TradeStatHash {
    private const val MIX = 0x5bd1e995

    // https://github.com/Kvan7/Exiled-Exchange-2/blob/master/dataParser/src/stores/helpers/hash_computer.py
    fun compute(statIds: List<String>): Long {
        val bytes = ByteArray(statIds.size * 4)
        statIds.forEachIndexed { index, statId ->
            val hash = murmur2(statId.toByteArray(Charsets.UTF_8), 0xc58f1a7b.toInt())
            repeat(4) { bytes[index * 4 + it] = (hash ushr (it * 8)).toByte() }
        }
        return murmur2(bytes, 0x02312233).toLong() and 0xffffffffL
    }

    private fun murmur2(bytes: ByteArray, seed: Int): Int {
        var hash = seed xor bytes.size
        var offset = 0
        while (offset + 4 <= bytes.size) {
            var word = 0
            repeat(4) { word = word or ((bytes[offset + it].toInt() and 0xff) shl (8 * it)) }
            word *= MIX
            word = (word xor (word ushr 24)) * MIX
            hash = (hash * MIX) xor word
            offset += 4
        }
        if (offset < bytes.size) {
            for (i in offset until bytes.size) {
                hash = hash xor ((bytes[i].toInt() and 0xff) shl ((i - offset) * 8))
            }
            hash *= MIX
        }
        hash = (hash xor (hash ushr 13)) * MIX
        return hash xor (hash ushr 15)
    }
}
