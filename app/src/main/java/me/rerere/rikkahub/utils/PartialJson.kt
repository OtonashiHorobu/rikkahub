package me.rerere.rikkahub.utils

/**
 * 流式到达的 JSON 参数里，某个字段已经收到的内容。
 *
 * [complete] 为 false 表示后续还有字节没到（字符串没闭合，或整个对象的 `}` 还没来）。
 */
data class PartialString(
    val value: String,
    val complete: Boolean,
)

private class StringScan(val value: String, val complete: Boolean, val next: Int)

private const val QUOTE = 34
private const val BACKSLASH = 92

/**
 * 容错解析**不完整**的 JSON 对象，返回已到达的顶层字段。
 *
 * 模型边生成边发参数，界面上拿到的通常是 `{"path":"/a/b` 这种半截内容，
 * 严格解析会失败并丢掉所有已到达的信息。这里只做一件保守的事：
 * 把顶层字段的已有内容读出来，读不全的字段返回前缀并标记 [PartialString.complete] = false。
 *
 * 解析失败一律返回已读到的部分，不抛异常。
 */
fun parsePartialJson(raw: String): Map<String, PartialString> {
    val result = LinkedHashMap<String, PartialString>()
    var i = 0
    while (i < raw.length && raw[i].isWhitespace()) i++
    if (i >= raw.length || raw[i] != '{') return result
    i++

    while (i < raw.length) {
        while (i < raw.length && (raw[i].isWhitespace() || raw[i] == ',')) i++
        if (i >= raw.length || raw[i] == '}') break
        if (raw[i] != '"') break

        val keyScan = readString(raw, i) ?: break
        // 键还没收全，整个字段都还不能用
        if (!keyScan.complete) break
        i = keyScan.next

        while (i < raw.length && raw[i].isWhitespace()) i++
        if (i >= raw.length || raw[i] != ':') break
        i++
        while (i < raw.length && raw[i].isWhitespace()) i++
        if (i >= raw.length) {
            result[keyScan.value] = PartialString("", false)
            break
        }

        if (raw[i] == '"') {
            val valueScan = readString(raw, i) ?: break
            result[keyScan.value] = PartialString(valueScan.value, valueScan.complete)
            if (!valueScan.complete) break
            i = valueScan.next
        } else {
            val scan = readBare(raw, i)
            result[keyScan.value] = PartialString(scan.value, scan.complete)
            if (!scan.complete) break
            i = scan.next
        }
    }
    return result
}

private fun readString(raw: String, start: Int): StringScan? {
    if (start >= raw.length || raw[start] != '"') return null
    val builder = StringBuilder()
    var i = start + 1
    while (i < raw.length) {
        val c = raw[i]
        if (c == '"') return StringScan(builder.toString(), true, i + 1)
        if (c.code != BACKSLASH) {
            builder.append(c)
            i++
            continue
        }
        if (i + 1 >= raw.length) {
            // 转义符刚发到一半，先不吐这半个字符，避免界面闪烁
            return StringScan(builder.toString(), false, raw.length)
        }
        val escaped = raw[i + 1]
        if (escaped == 'u') {
            val code = raw.substringOrNull(i + 2, i + 6)?.toIntOrNull(16)
            if (code == null) return StringScan(builder.toString(), false, raw.length)
            builder.append(code.toChar())
            i += 6
            continue
        }
        builder.append(
            when (escaped) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> 12.toChar()
                else -> escaped
            }
        )
        i += 2
    }
    return StringScan(builder.toString(), false, raw.length)
}

/** 读数字、true/false/null 或嵌套的对象/数组，遇到顶层的 `,` 或 `}` 停 */
private fun readBare(raw: String, start: Int): StringScan {
    var i = start
    var depth = 0
    var inString = false
    var escaped = false
    while (i < raw.length) {
        val c = raw[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (c.code == BACKSLASH) {
                escaped = true
            } else if (c == '"') {
                inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    if (depth == 0) return StringScan(raw.substring(start, i).trim(), true, i)
                    depth--
                }
                ',' -> if (depth == 0) return StringScan(raw.substring(start, i).trim(), true, i)
            }
        }
        i++
    }
    return StringScan(raw.substring(start).trim(), false, raw.length)
}

private fun String.substringOrNull(start: Int, end: Int): String? =
    if (start < 0 || end > length || start > end) null else substring(start, end)

/**
 * 把容错解析出的字段拼成一个 [kotlinx.serialization.json.JsonObject]。
 *
 * 只用于生成中的临时展示：没收全的字段按前缀发出去，渲染器读到的就是"到目前为止的内容"。
 */
fun Map<String, PartialString>.toJsonObject(): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.JsonObject(
        mapValues { (_, value) -> kotlinx.serialization.json.JsonPrimitive(value.value) }
    )
