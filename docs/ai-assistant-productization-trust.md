# AI Assistant Trust Productization Contract

This contract turns the assistant from raw command generation into a measured, review-first schedule assistant.

## Measurement spine

- Deterministic fixtures: `backend/src/test/resources/ai-scenarios/`.
- Runner: `AiScenarioEvaluationHarnessTest`.
- Hard-fail safety: unsafe executable delete, external direct deletion, missing required clarification, and unexpected DB diff candidates.
- Manual live smoke: `scripts/llm-live-probe.ps1 -ScenarioSetPath ...` writes sanitized `.omx/reports/llm-live-probe-*.json` artifacts.

## Server-owned policy

`AssistantPolicyService` owns preflight and postflight guardrails that were previously embedded in the orchestrator:

- recurring routine scope clarification,
- travel/date clarification,
- leave/status impact analysis,
- destructive candidate confirmation,
- after-work creation defaults,
- availability candidate selection,
- ambiguous follow-up clarification,
- event conflict guard.

The orchestrator remains the coordinator: interpret → policy preflight → draft → policy postflight → validate/match → repair → final proposal.

## Context Engine v2 scaffold

`AiContextPackageBuilder` produces a `ContextPackage` containing:

- request type,
- temporal scope,
- included sections and reasons,
- excluded sections and reasons,
- character estimate,
- privacy exposure score.

Availability windows, history, weekly blocks, and task sections are included only when useful for the request type.

## Decision package / trust UX contract

`AiDecisionPackage` wraps the legacy command batch without changing apply semantics. It exposes:

- request kind,
- trust level,
- scope,
- analysis,
- proposal,
- user effort,
- privacy,
- Korean trust card sections:
  - “이렇게 이해했습니다”
  - “바꾸려는 항목”
  - “건드리지 않는 항목”
  - “외부 일정이라 직접 바꾸지 않는 항목”
  - “확인이 필요한 이유”
  - “적용 전 변경 요약”

## Privacy / telemetry rules

Allowed report fields: scenario id, endpoint, message length/hash, latency, validation outcome, command count, decision package, safety verdict, and estimated cost when available.

Forbidden by default: raw prompts, API keys, full calendar descriptions, provider internals, and reasoning traces.

## Differentiation versus native calendar AI

The product differentiates through 5-minute orchestration, local routine/workload context, explicit external-calendar protection, and approval-first diff control. The assistant should make review easier, not hide risk behind opaque AI prose.

## AiDecisionPackage v2 contract (2026-06-06)

`AiDecisionPackage` is now the product-facing trust package, not just a wrapper over `StructuredAiCommandBatch`. API/UI consumers must treat it as the source of truth for explaining AI scheduling decisions.

Required stable fields:
- `requestKind`, `trustLevel`, `scope`
- `understanding`
- `affectedItems`, `protectedItems`, `externalBlockedItems`
- `proposedChanges`
- `requiresConfirmation`, `confirmationReason`, `clarificationQuestion`, `riskLevel`
- `privacy`
- ordered `displaySections`

UI cards must render the ordered display sections so users can see what the assistant understood, what it may change, what it will not touch, which external items are blocked, why confirmation is required, and the apply-time change summary. Internal wire values such as `PENDING` or raw provider/debug labels must not be shown as user copy.

The package must never expose chain-of-thought, raw prompts, provider metadata, validation traces, Authorization headers, API keys, or unsanitized full calendar descriptions.
