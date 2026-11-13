package com.hermes.chatandroid.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 서버 응답은 관대하게 해석한다: 알 수 없는 키 무시, 느슨한 따옴표 등. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun parseJsonOrNull(text: String): JsonElement? =
    try {
        if (text.isBlank()) null else AppJson.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }

fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

/** 문자열/숫자/불리언 primitive 를 문자열로. null/비primitive -> null. */
fun JsonElement?.asText(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

/** Int 또는 "3" 같은 숫자 문자열 또는 3.0 -> Int. 그 외 null. */
fun JsonElement?.asIntLenient(): Int? {
    val s = asText()?.trim() ?: return null
    return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt()
}

fun JsonElement?.asLongLenient(): Long? {
    val s = asText()?.trim() ?: return null
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
}

fun JsonElement?.asDoubleLenient(): Double? = asText()?.trim()?.toDoubleOrNull()

/** true/false 또는 0/1 (정수·문자열). 그 외 null. */
fun JsonElement?.asBoolLenient(): Boolean? =
    when (asText()?.trim()?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

fun JsonObject.text(key: String): String? = this[key].asText()
fun JsonObject.int(key: String): Int? = this[key].asIntLenient()
fun JsonObject.bool(key: String): Boolean? = this[key].asBoolLenient()
fun JsonObject.array(key: String): JsonArray? = this[key].asArray()
fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

/**
 * 봇 tone: 정수 또는 문자열 -> 1..6 이면 Int, "0"/0/빈 문자열/비숫자는 null.
 */
fun JsonElement?.asTone(): Int? {
    val n = asIntLenient() ?: return null
    return if (n in 1..6) n else null
}
