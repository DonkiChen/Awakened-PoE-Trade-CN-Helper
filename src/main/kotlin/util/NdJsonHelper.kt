package util

import com.google.gson.reflect.TypeToken
import java.io.File

object NdJsonHelper {
    inline fun <reified T> readNdJson(file: File): List<T> {
        if (!file.exists()) {
            return emptyList()
        }
        val result = mutableListOf<T>()
        file.reader().useLines { sequence ->
            sequence.forEach { json ->
                val entity = runCatching {
                    fromJson<T>(json)
                }.onFailure {
                    println(json)
                }.getOrThrow()
                result.add(entity)
            }
        }
        return result
    }

    inline fun <reified T> readNdJsonWithJson(file: File): List<Pair<T, String>> {
        if (!file.exists()) {
            return emptyList()
        }
        val result = mutableListOf<Pair<T, String>>()
        file.reader().useLines { sequence ->
            sequence.forEach { json ->
                val entity = runCatching {
                    fromJson<T>(json)
                }.onFailure {
                    println(json)
                }.getOrThrow()
                result.add(entity to json)
            }
        }
        return result
    }

}