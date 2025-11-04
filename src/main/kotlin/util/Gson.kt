package util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Reader

val gson = Gson()

inline fun <reified T> fromJson(json: String): T = gson.fromJson(json, object : TypeToken<T>() {}.type)

inline fun <reified T> fromJson(reader: Reader): T = gson.fromJson(reader, object : TypeToken<T>() {}.type)