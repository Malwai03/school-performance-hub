package com.schoolapp.data.local

import androidx.room.TypeConverter
import org.json.JSONObject

class AppTypeConverters {

    @TypeConverter
    fun mapToJson(value: Map<String, Double>): String {
        val json = JSONObject()
        value.forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }

    @TypeConverter
    fun jsonToMap(value: String): Map<String, Double> {
        val json = JSONObject(value)
        return json.keys().asSequence().associateWith { key -> json.optDouble(key) }
    }
}

fun Map<String, Double>.toJsonString(): String {
    val json = JSONObject()
    forEach { (k, v) -> json.put(k, v) }
    return json.toString()
}

fun String.toSubjectMap(): Map<String, Double> {
    val json = JSONObject(this)
    return json.keys().asSequence().associateWith { key -> json.optDouble(key) }
}
