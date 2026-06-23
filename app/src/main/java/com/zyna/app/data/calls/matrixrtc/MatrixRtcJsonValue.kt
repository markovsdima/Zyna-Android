package com.zyna.app.data.calls.matrixrtc

import org.json.JSONArray
import org.json.JSONObject

sealed interface MatrixRtcJsonValue {
    data object Null : MatrixRtcJsonValue
    data class Bool(val value: Boolean) : MatrixRtcJsonValue
    data class Integer(val value: Long) : MatrixRtcJsonValue
    data class Number(val value: Double) : MatrixRtcJsonValue
    data class StringValue(val value: String) : MatrixRtcJsonValue
    data class ArrayValue(val value: List<MatrixRtcJsonValue>) : MatrixRtcJsonValue
    data class ObjectValue(val value: Map<String, MatrixRtcJsonValue>) : MatrixRtcJsonValue

    val stringValue: String?
        get() = (this as? StringValue)?.value
}

internal fun MatrixRtcJsonValue.toJsonValue(): Any {
    return when (this) {
        MatrixRtcJsonValue.Null -> JSONObject.NULL
        is MatrixRtcJsonValue.Bool -> value
        is MatrixRtcJsonValue.Integer -> value
        is MatrixRtcJsonValue.Number -> value
        is MatrixRtcJsonValue.StringValue -> value
        is MatrixRtcJsonValue.ArrayValue -> JSONArray().also { array ->
            value.forEach { array.put(it.toJsonValue()) }
        }
        is MatrixRtcJsonValue.ObjectValue -> JSONObject().also { obj ->
            value.forEach { (key, jsonValue) -> obj.put(key, jsonValue.toJsonValue()) }
        }
    }
}

internal fun matrixRtcJsonValueFrom(value: Any?): MatrixRtcJsonValue {
    return when (value) {
        null, JSONObject.NULL -> MatrixRtcJsonValue.Null
        is Boolean -> MatrixRtcJsonValue.Bool(value)
        is Byte -> MatrixRtcJsonValue.Integer(value.toLong())
        is Short -> MatrixRtcJsonValue.Integer(value.toLong())
        is Int -> MatrixRtcJsonValue.Integer(value.toLong())
        is Long -> MatrixRtcJsonValue.Integer(value)
        is Float -> MatrixRtcJsonValue.Number(value.toDouble())
        is Double -> MatrixRtcJsonValue.Number(value)
        is String -> MatrixRtcJsonValue.StringValue(value)
        is JSONArray -> MatrixRtcJsonValue.ArrayValue(
            (0 until value.length()).map { index -> matrixRtcJsonValueFrom(value.get(index)) }
        )
        is JSONObject -> MatrixRtcJsonValue.ObjectValue(
            value.keys().asSequence().associateWith { key ->
                matrixRtcJsonValueFrom(value.get(key))
            }
        )
        else -> MatrixRtcJsonValue.StringValue(value.toString())
    }
}

internal fun JSONObject.stringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return getString(name)
}

internal fun JSONObject.longOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return getLong(name)
}

internal fun JSONObject.putIfNotNull(name: String, value: Any?) {
    if (value != null) {
        put(name, value)
    }
}

