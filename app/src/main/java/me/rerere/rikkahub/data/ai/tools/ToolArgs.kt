package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 工具参数的取值帮助函数：缺省、类型不对都返回 null，交给各工具自己决定默认值。 */

internal fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.hasArg(name: String): Boolean = jsonObjectOrNull()?.containsKey(name) == true

internal fun JsonElement.strArg(name: String): String =
    (jsonObjectOrNull()?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

/** 参数存在且非空才返回，用于「只改传了的字段」 */
internal fun JsonElement.strOrNull(name: String): String? =
    strArg(name).trim().takeIf { it.isNotEmpty() && hasArg(name) }

internal fun JsonElement.boolArg(name: String): Boolean? =
    (jsonObjectOrNull()?.get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.toBooleanStrictOrNull()

internal fun JsonElement.intArg(name: String): Int? =
    (jsonObjectOrNull()?.get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.toIntOrNull()

internal fun JsonElement.doubleArg(name: String): Double? =
    (jsonObjectOrNull()?.get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.toDoubleOrNull()

internal fun JsonElement.stringListArg(name: String): List<String>? =
    (jsonObjectOrNull()?.get(name) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** 工具里可预期的输入错误：转成 {"error": ...} 回给模型，而不是抛栈 */
internal class ToolInputException(message: String) : Exception(message)

internal suspend fun toolResult(
    block: suspend () -> List<me.rerere.ai.ui.UIMessagePart>,
): List<me.rerere.ai.ui.UIMessagePart> = try {
    block()
} catch (e: ToolInputException) {
    listOf(
        me.rerere.ai.ui.UIMessagePart.Text(
            buildJsonObject { put("error", e.message.orEmpty()) }.toString()
        )
    )
}
