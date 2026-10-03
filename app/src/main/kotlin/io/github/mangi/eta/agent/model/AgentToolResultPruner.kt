package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** DSH-style deterministic head/middle/tail pruning for oversized tool text. */
internal object AgentToolResultPruner {
    const val THRESHOLD_CHARS = 8_192
    const val HEAD_CHARS = 4_096
    const val TAIL_CHARS = 1_024
    const val MARKER = "\n\n[... tool result middle pruned ...]\n\n"

    data class Result(val messages: JSONArray, val changed: Boolean, val charsRemoved: Int)

    fun prune(messages: JSONArray, sensitiveIds: Set<String> = emptySet()): Result {
        val result = JSONArray()
        var changed = false
        var charsRemoved = 0
        for (index in 0 until messages.length()) {
            val original = messages.optJSONObject(index)
            if (original == null) {
                result.put(messages.opt(index))
                continue
            }
            if (original.optString("role") != "tool" || original.optString("tool_call_id") in sensitiveIds || original.opt("content") !is String) {
                result.put(original)
                continue
            }
            val text = original.optString("content")
            val length = text.codePointCount(0, text.length)
            if (length <= THRESHOLD_CHARS) {
                result.put(original)
                continue
            }
            val head = text.offsetByCodePoints(0, HEAD_CHARS)
            val tail = text.offsetByCodePoints(0, length - TAIL_CHARS)
            val shortened = text.substring(0, head) + MARKER + text.substring(tail)
            val shortenedLength = shortened.codePointCount(0, shortened.length)
            if (shortenedLength >= length || shortenedLength > THRESHOLD_CHARS) {
                result.put(original)
                continue
            }
            result.put(JSONObject(original.toString()).put("content", shortened))
            changed = true
            charsRemoved += length - shortenedLength
        }
        return Result(result, changed, charsRemoved)
    }
}
