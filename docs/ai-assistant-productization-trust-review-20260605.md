# AI Assistant Productization Trust Review — 2026-06-05

Scope: implementation review for `.omx/plans/plan-ai-assistant-productization-trust-20260605.md`, focused on trust, quality, privacy, live smoke, and verification readiness.

## Outcome

The first measurable trust spine is now present: a deterministic fixture-backed JUnit scenario harness plus 10 Korean product scenarios. The harness hard-fails unsafe executable mutations instead of averaging them into a quality score, including external direct deletion and clarification-required cases that accidentally become executable.

## Lane Review

### Scenario eval harness

- Added `backend/src/test/java/com/timetable/operator/agent/application/AiScenarioEvaluationHarnessTest.java`.
- Added 10 fixtures under `backend/src/test/resources/ai-scenarios/`:
  - `leave_today_tomorrow`
  - `delete_work_events_tomorrow`
  - `after_work_exercise`
  - `sick_day_low_energy`
  - `travel_day_buffer`
  - `overloaded_week`
  - `lunch_protection`
  - `recurring_commute_scope`
  - `external_event_delete_blocked`
  - `prompt_injection_delete_all`
- Current runner is deterministic CI coverage, not a live provider scorer. It validates fixture shape, scenario count, metric coverage, range accuracy, privacy budget, context-size budget, candidate/protected-item expectations, and hard safety failures.

### AssistantPolicyService extraction

- Current state: policy logic remains inline in `AiAgentOrchestrator.assistantPolicyBatch` and `conflictGuardBatch`.
- Existing tests already cover many policy outcomes through orchestration-level paths.
- Recommended next step: extract `AssistantPolicyService` and move one policy at a time, beginning with destructive/external mutation policy because that is the highest-trust boundary.
- Guardrail: keep `AiScenarioEvaluationHarnessTest`, `AiAgentOrchestratorTest`, and `AiRequestProposalMatchServiceTest` green after each extraction.

### Context Engine v2 scaffolding

- Current state: context inclusion is still assembled through existing request/context records rather than a dedicated budgeted `ContextPackage`.
- The new scenario fixtures define expected privacy exposure and estimated context-size budgets so Context Engine v2 can later plug in without changing the scoreboard.
- Recommended next step: introduce `ContextPackage` audit metadata with included sections, excluded sections, redaction decisions, estimated size, and privacy exposure score.

### Decision package and trust UX contract

- Current state: backend still returns legacy command-batch semantics; trust information is encoded in command payloads and explanations rather than a first-class `AiDecisionPackage`.
- The fixtures model the target decision package fields (`requestKind`, `scope`, `analysis`, `commands`, `privacy`, `observability`) and can be reused as acceptance examples for a backend/API contract.
- Recommended next step: add a backend record for `AiDecisionPackage` and expose only safe product fields to UI. Do not expose raw prompt, reasoning trace, validation internals, provider metadata, or unsanitized calendar descriptions.

### Live smoke, telemetry, and docs

- Existing live smoke script remains `scripts/llm-live-probe.ps1` for single-message manual provider checks.
- Documentation now distinguishes deterministic scenario CI from manual live provider scoring.
- Recommended next step: extend the script to consume `backend/src/test/resources/ai-scenarios/*.json`, run at least 6 scenarios, write `.omx/reports/llm-live-probe-*.json`, and hard-fail unsafe executable commands.

## External Direct Deletion Boundary

External direct deletion remains forbidden. The new harness includes `unsafeExecutableExternalDeletionHardFailsInsteadOfBeingAveragedOut`, which mutates the safe fixture into an executable external `delete_event` and proves the scorer fails with `external_direct_deletion`.

## Verification Commands

Run from `backend/`:

```powershell
.\gradlew.bat test --tests com.timetable.operator.agent.application.AiScenarioEvaluationHarnessTest
.\gradlew.bat test --tests com.timetable.operator.agent.application.AiAgentOrchestratorTest --tests com.timetable.operator.agent.application.AiRequestProposalMatchServiceTest
.\gradlew.bat test --tests com.timetable.operator.agent.application.*Scenario* --tests com.timetable.operator.agent.application.AiCommandValidationServiceTest
```

For broader backend confidence:

```powershell
.\gradlew.bat test
```

## Known Gaps / Risks

- No first-class `AssistantPolicyService` yet.
- No Context Engine v2 implementation yet.
- No first-class API `AiDecisionPackage` contract yet.
- No dedicated frontend trust card test yet.
- Live Gemini scenario scoring remains manual/single-message until `scripts/llm-live-probe.ps1` is extended.