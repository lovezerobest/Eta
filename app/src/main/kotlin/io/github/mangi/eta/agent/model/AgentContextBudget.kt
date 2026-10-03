package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Provider usage anchors prompt pressure; heuristics price only later deltas. */
internal class AgentContextBudget(
    private val window: Int?,
    private val reservedCompletionTokens: Int = 0,
) {
    private data class Anchor(val pressureTokens: Int, val requestEstimate: Int)
    private var anchor: Anchor? = null

    fun observe(usage: AgentTokenUsage?, requestEstimate: Int, promptTokensOverride: Int? = null) {
        val pressure = promptTokensOverride ?: usage?.promptTokens ?: return
        if (pressure >= 0 && requestEstimate > 0) anchor = Anchor(pressure, requestEstimate)
    }

    fun pressure(messages: JSONArray, tools: JSONArray): AgentContextPressure {
        val heuristic = rawEstimate(messages, tools)
        val current = anchor
        val projected = current?.let { max(0, it.pressureTokens + heuristic - it.requestEstimate) }
        return AgentContextPressure(
            pressureTokens = current?.pressureTokens,
            projectedTokens = projected,
            contextWindow = window?.takeIf { it > 0 },
            heuristicTokens = heuristic,
            thresholdTokens = thresholdTokens(),
            retainTokens = retentionTokens(),
        )
    }

    fun estimate(messages: JSONArray, tools: JSONArray): Int =
        pressure(messages, tools).projectedTokens ?: rawEstimate(messages, tools)

    fun shouldCompact(tokens: Int): Boolean = thresholdTokens()?.let { tokens >= it } == true
    fun exceedsWindow(tokens: Int): Boolean = window?.takeIf { it > 0 }?.let { tokens >= it } == true

    fun thresholdTokens(): Int? {
        val capacity = window?.takeIf { it > 0 } ?: return null
        val messageBudget = capacity - reservedCompletionTokens.coerceAtLeast(0)
        if (messageBudget <= 0) return null
        // Match DSH's 65,536 headroom when it fits. Small windows use one
        // quarter of their message budget so headroom cannot consume capacity.
        val headroom = if (messageBudget > DEFAULT_HEADROOM_TOKENS) {
            DEFAULT_HEADROOM_TOKENS
        } else {
            messageBudget / 4
        }
        val pressureBudget = messageBudget - headroom
        if (pressureBudget <= 0) return null
        return min(floor(capacity * TRIGGER_RATIO).toInt(), pressureBudget).coerceAtLeast(1)
    }

    fun retentionTokens(): Int? {
        val capacity = window?.takeIf { it > 0 } ?: return null
        val messageBudget = capacity - reservedCompletionTokens.coerceAtLeast(0)
        if (messageBudget <= 0) return null
        return floor(messageBudget * RETAIN_RATIO).toInt().coerceAtLeast(1)
    }

    companion object {
        const val TRIGGER_RATIO = 0.80
        const val RECENT_MESSAGES = 4
        const val RETAIN_RATIO = 0.16
        const val DEFAULT_HEADROOM_TOKENS = 65_536
        const val MAX_OVERFLOW_ATTEMPTS = 3

        private const val CHARS_PER_TOKEN = 4
        private const val BLOCK_OVERHEAD = 4
        private const val ROLE_OVERHEAD = 4

        /** DSH fixed-density heuristic: four UTF-16 characters per token. */
        fun textTokens(text: String): Int = ceil(text.length.toDouble() / CHARS_PER_TOKEN).toInt()

        /** Price the model-visible message surface, excluding JSON envelope noise. */
        fun rawEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int {
            var tokens = if (tools.length() == 0) 0 else textTokens(tools.toString()) + BLOCK_OVERHEAD
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                tokens += estimateMessage(message)
            }
            return tokens
        }

        private fun estimateMessage(message: JSONObject): Int {
            var tokens = ROLE_OVERHEAD
            val content = message.opt("content")
            tokens += when {
                content is JSONArray -> estimateContent(content)
                content is String -> textTokens(content)
                content == null || content == JSONObject.NULL -> 0
                else -> textTokens(content.toString())
            }
            if (message.has("tool_calls")) {
                tokens += textTokens(message.opt("tool_calls")?.toString().orEmpty()) + BLOCK_OVERHEAD
            }
            if (message.has("tool_call_id")) {
                tokens += textTokens(message.optString("tool_call_id")) + BLOCK_OVERHEAD
            }
            if (message.has("reasoning_content")) {
                tokens += textTokens(message.optString("reasoning_content")) + BLOCK_OVERHEAD
            }
            // Eta keeps wake-time screen text outside the durable user content;
            // it still belongs to this provider request's pressure estimate.
            if (message.has("_eta_assistant_screen_context")) {
                tokens += textTokens(message.optString("_eta_assistant_screen_context")) + BLOCK_OVERHEAD
            }
            return tokens
        }

        private fun estimateContent(parts: JSONArray): Int {
            var tokens = 0
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                val type = part.optString("type")
                val structural = JSONObject(part.toString())
                if (type == "image_url") {
                    val image = structural.optJSONObject("image_url")
                    if (image?.optString("url")?.startsWith("data:image/", ignoreCase = true) == true) {
                        image.put("url", "[image payload]")
                    }
                }
                val text = if (type in setOf("text", "input_text", "reasoning")) {
                    part.optString("text")
                } else structural.toString()
                tokens += textTokens(text) + BLOCK_OVERHEAD
            }
            return tokens
        }
    }
}

internal data class AgentContextPressure(
    val pressureTokens: Int?,
    val projectedTokens: Int?,
    val contextWindow: Int?,
    val heuristicTokens: Int,
    val thresholdTokens: Int?,
    val retainTokens: Int?,
)

/** Output reservation follows the concrete request cap when Eta knows one. */
internal fun AgentContextBudget.Companion.outputReservation(
    config: AgentModelClient.ModelConfig,
): Int {
    val fields = listOf("max_completion_tokens", "max_output_tokens", "max_tokens")
    val custom = config.customBody.asReversed().firstNotNullOfOrNull { field ->
        if (field.key !in fields) null else field.value.toString().trim('"').toIntOrNull()
    }
    if (custom != null && custom > 0) return custom
    val extra = runCatching { JSONObject(config.extraBodyJson) }.getOrNull()
    fields.forEach { key -> extra?.optInt(key)?.takeIf { it > 0 }?.let { return it } }
    if (config.providerType == io.github.mangi.eta.data.model.ProviderTypes.ANTHROPIC) {
        return if (config.effectiveReasoningEffort in setOf(
                io.github.mangi.eta.data.model.ReasoningEffort.XHIGH,
                io.github.mangi.eta.data.model.ReasoningEffort.MAX,
            )) 65_536 else 4_096
    }
    return 0
}
