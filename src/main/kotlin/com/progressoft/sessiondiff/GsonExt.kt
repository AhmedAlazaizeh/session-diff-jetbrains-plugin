package com.progressoft.sessiondiff

import com.google.gson.JsonElement

// Real Claude Code transcript lines contain explicit JSON nulls and fields whose type
// varies by message shape — a raw .asString/.asInt/.asJsonObject/.asJsonArray throws
// (UnsupportedOperationException/ClassCastException) instead of returning null, so every
// access from transcript JSON must route through these instead of the raw Gson accessors.
fun JsonElement?.jsonString(): String? = this?.takeIf { it.isJsonPrimitive }?.asString
fun JsonElement?.jsonInt(): Int? = this?.takeIf { it.isJsonPrimitive }?.asInt
fun JsonElement?.jsonBoolean(): Boolean? = this?.takeIf { it.isJsonPrimitive }?.asBoolean
fun JsonElement?.jsonObject() = this?.takeIf { it.isJsonObject }?.asJsonObject
fun JsonElement?.jsonArray() = this?.takeIf { it.isJsonArray }?.asJsonArray
