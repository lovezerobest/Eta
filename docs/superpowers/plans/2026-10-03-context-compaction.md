# DSH-Aligned Context Compaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align Eta's end-to-end compaction behavior with DSH while adapting it to Eta's provider and conversation architecture, and reduce unnecessary compaction latency/work.

**Architecture:** Preserve Eta's current compaction candidate/atomic-commit safety, then adjust the pressure trigger and region selection to DSH semantics, add only demonstrably safe tool-result pruning, and build summarization requests from a provider-compatible replayable prefix with the compaction directive at the end. Validate provider overflow recovery and remeasure after each bounded attempt.

**Tech Stack:** Kotlin, Android app module, org.json, JUnit, existing Eta provider abstraction.

**Spec:** `docs/superpowers/specs/2026-10-03-context-compaction-design.md`

## Global Constraints

- DSH default trigger is 80%, verbatim retention is 16%, and headroom is 65,536 tokens subject to Eta's model/output budget semantics.
- Overflow recovery bypasses the ordinary trigger and retention restriction but never breaks complete tool-call/result boundaries.
- Model-generated summaries remain; failed or invalid summaries must not replace original history.
- Unknown or semantically important tool results must not be blindly truncated.
- Preserve Eta privacy filtering, roleplay rules, provider compatibility, and atomic snapshot persistence.

## Review Focus

- Missing/incorrect routed usage or unknown capacity: retain safe estimation and do not compact solely based on invented capacity.
- Small windows or output reservation consuming capacity: budget calculation must remain valid and tested.
- Multiple/open tool calls and missing results: region selection and pruning cannot break pair integrity.
- Provider-specific summary protocol incompatibilities: no tools execute; compatible route gets replay prefix and trailing directive.
- Cancellation, persistence errors, and overflow during summarization: original context remains recoverable and retries remain bounded.

---

### Task 1: Match DSH region selection and overflow semantics

**Files:**
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentContextBudget.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentContextCompactor.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentContextSession.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentLoop.kt`
- Test: `app/src/test/kotlin/io/github/mangi/eta/agent/model/AgentContextCompactionTest.kt`

**Interfaces:** Consume existing `AgentContextBudget`, `AgentContextCompactor.canSplit`, and `AgentContextSession.compact`; no public API change.

- [ ] Add tests for DSH region retention by token budget rather than fixed message count, including assistant tool batches at the cutoff.
- [ ] Add tests proving overflow compaction ignores ordinary trigger/retention constraints but preserves latest unresolved work and complete tool batches.
- [ ] Run focused compaction tests and verify the new tests fail for the old behavior.
- [ ] Implement tail accumulation using the existing Eta request-surface token estimator and safe split predicate; keep system head outside the selected region.
- [ ] Verify pressure decisions still use actual usage anchors and config context/output reservation; adjust only discrepancies from DSH policy and preserve unknown-window behavior.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*AgentContextCompactionTest'` and verify PASS.

### Task 2: Audit and implement safe model-free tool-result pruning

**Files:**
- Modify or create focused helper under `app/src/main/kotlin/io/github/mangi/eta/agent/model/` only if Eta tool-result format proves safely classifiable.
- Modify: `AgentContextSession.kt` / `AgentLoop.kt` only for orchestration if required.
- Test: `AgentContextCompactionTest.kt` or a focused new `AgentToolResultPrunerTest.kt`.

**Interfaces:** Pruner consumes current serialized conversation plus validated tool-result classifications; returns a candidate surface or no-op. It must not mutate the live source before validation.

- [ ] Inspect existing tool-result schemas and DSH optional pruner implementation; record exact eligible result types in tests.
- [ ] If a result type is safely prunable, test eligible result reduction, unknown tool preservation, missing-result handling, and pair integrity.
- [ ] Implement only those proven-safe classifications as a candidate transformation; otherwise keep pruning as an explicit no-op and document the compatibility gap rather than generic truncation.
- [ ] Orchestrate pruning before region selection on pressure and overflow paths, remeasure after changes, and ensure pruned progress is persisted safely.
- [ ] Run focused tests and confirm failure/cancellation does not corrupt the live conversation.

### Task 3: Replay compatible provider prefix for summarization

**Files:**
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentContextCompactor.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentContextSession.kt` or `AgentLoop.kt` to pass the routed summary surface if necessary.
- Modify: provider request construction only where compatibility tests require it.
- Test: `app/src/test/kotlin/io/github/mangi/eta/agent/model/AgentContextCompactionTest.kt`
- Test: `app/src/test/kotlin/io/github/mangi/eta/agent/model/AgentCompactionProviderTest.kt`

**Interfaces:** Keep compaction internal. Any added input type explicitly carries replay prefix, selected region, compatible tools, and model purpose; summary remains tool-disabled.

- [ ] Add tests asserting summary request retains compatible system/history prefix and places directive after replayed history; ensure normal chat prompt and sensitive data are not leaked.
- [ ] Add protocol tests for compatible and incompatible provider message/tool representations; assert no summary path executes tools.
- [ ] Run focused provider/compaction tests and verify expected failures.
- [ ] Implement DSH-style trailing compaction instruction while retaining Eta roleplay-specific constraints, image simplification, sensitivity redaction, and output validation.
- [ ] Run focused tests and verify all provider protocols pass.

### Task 4: Verify bounded retries, persistence, and whole-suite behavior

**Files:**
- Modify tests in `AgentContextCompactionTest.kt` and related existing context/provider tests as required.
- Modify production code only for demonstrated gaps from Tasks 1-3.

**Interfaces:** No new public interfaces.

- [ ] Add/adjust tests for summary overflow splitting at complete groups, bounded repeated compaction, invalid summary, cancellation, and persistence failure.
- [ ] Run focused test set: `./gradlew :app:testDebugUnitTest --tests '*AgentContextCompactionTest' --tests '*AgentCompactionProviderTest'`.
- [ ] Run full unit suite: `./gradlew :app:testDebugUnitTest`.
- [ ] Build: `./gradlew :app:assembleDebug`.
- [ ] Review diff and report measured request-size/retry effects; do not claim cache hits or latency reduction without repeatable end-to-end measurements.
