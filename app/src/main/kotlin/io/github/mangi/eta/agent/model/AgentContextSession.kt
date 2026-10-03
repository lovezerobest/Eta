package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
) {
    val budget = AgentContextBudget(config.contextWindow, AgentContextBudget.outputReservation(config))
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(roundTools: JSONArray, force: Boolean = false, final: Boolean = false) {
        val before = budget.estimate(messages, roundTools)
        if (!force && !budget.shouldCompact(before)) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        val startedAtNanos = System.nanoTime()
        val summaryRequests = AtomicInteger(0)
        val progressActive = AtomicBoolean(true)
        val progressLock = Any()
        fun elapsedMs(): Long = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        fun compactionEvent(phase: String, tokensAfter: Int? = null, reasonCode: String = "") =
            AgentEvent.ContextCompaction(
                operationId = operation,
                phase = phase,
                tokensBefore = before,
                tokensAfter = tokensAfter,
                reasonCode = reasonCode,
                summaryRequests = summaryRequests.get(),
                elapsedMs = elapsedMs(),
            )

        onEvent(compactionEvent(AgentEvent.ContextCompaction.PHASE_STARTED))
        val progressTask = COMPACTION_PROGRESS_EXECUTOR.scheduleAtFixedRate({
            synchronized(progressLock) {
                if (progressActive.get() && summaryRequests.get() > 0) {
                    runCatching { onEvent(compactionEvent(AgentEvent.ContextCompaction.PHASE_STARTED)) }
                }
            }
        }, PROGRESS_INTERVAL_MS, PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        fun stopProgress() {
            synchronized(progressLock) { progressActive.set(false) }
            progressTask.cancel(false)
        }
        try {
            var candidate = messages
            var attempts = 0
            do {
                val compactor = AgentContextCompactor(
                    config = config,
                    provider = provider,
                    controller = runController,
                    roleplay = roleplay,
                    onSummaryRequestStarted = {
                        summaryRequests.incrementAndGet()
                        synchronized(progressLock) {
                            if (progressActive.get()) onEvent(compactionEvent(AgentEvent.ContextCompaction.PHASE_STARTED))
                        }
                    },
                )
                candidate = compactor.compact(
                    candidate, systemCount, sensitiveIds(), force, tools = roundTools,
                )
                attempts++
                val tokens = budget.estimate(candidate, roundTools)
                if (!budget.shouldCompact(tokens)) break
                if (attempts >= AgentContextBudget.MAX_OVERFLOW_ATTEMPTS) {
                    throw AgentContextCompactor.failure("CONTEXT_NO_REDUCTION", "摘要后上下文仍超过容量预算。")
                }
            } while (true)
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            stopProgress()
            onEvent(compactionEvent(
                AgentEvent.ContextCompaction.PHASE_COMPLETED,
                tokensAfter = budget.estimate(messages, roundTools),
            ))
        } catch (failure: Exception) {
            stopProgress()
            runController.throwIfCancelled()
            onEvent(compactionEvent(
                phase = "failed",
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED",
            ))
            if (!final && (force || budget.exceedsWindow(before))) throw failure
            if (final) {
                // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
                committedSnapshot = createSnapshot(messages)
            }
        } finally {
            stopProgress()
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }
    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
        val COMPACTION_PROGRESS_EXECUTOR = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "eta-context-compaction-progress").apply { isDaemon = true }
        }
    }
}
