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
