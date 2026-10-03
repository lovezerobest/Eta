package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    /**
     * Prompt-side pressure used by the context meter.
     *
     * Provider `total_tokens` includes the generated answer and must not be
     * displayed or used as the next prompt's context occupancy. Providers that
     * report only total usage are normalized by subtracting output tokens.
     */
    val promptTokens: Int?
        get() = inputTokens ?: contextTokens?.let {
            (it - (outputTokens ?: 0)).coerceAtLeast(0)
        }

    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null
}
