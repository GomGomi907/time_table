# Time Table Development Checklist

Date: 2026-04-26

## Operating Rules

- Docker packaging is deferred until the product flow is stable.
- Prefer DB-backed state over browser-only persistence.
- Keep frontend display logic thin; move screen contracts into backend DTOs.
- Add or update tests with each backend contract change.
- Keep integration capability explicit in API fields and UI copy.

## Phase 1. User State And Onboarding

- [x] Persist onboarding completion in `onboarding_profiles`.
- [x] Persist core rhythm answers: wake, work start, dinner, sleep, weekend style.
- [x] Keep onboarding completion, answers, and first-day handoff state DB-backed; do not use `localStorage` for onboarding handoff.
- [x] Cover onboarding status, answers, completion, and suggestion application with backend tests.
- [x] Extend onboarding profile for detailed focus preferences when UX is fixed.

## Phase 2. Dashboard Contract

- [x] Add `GET /api/dashboard/summary`.
- [x] Include week schedule, goals, focus, sync, and suggestions in one dashboard response.
- [x] Switch `dashboard-view` to the summary endpoint.
- [x] Add dashboard summary controller test.
- [x] Move dashboard-only derived metrics, such as weekly shape score and top goal summary, into backend DTOs.

## Phase 3. Honest Sync State

- [x] Mark initial sync adapter mode as `scaffold`.
- [x] Expose external read/write readiness flags in sync meta.
- [x] Update dashboard sync copy so it does not imply provider diff is applied.
- [x] Cover sync capability meta in backend tests.
- [x] Split real Google Calendar and Tasks inbound sync into a separate implementation phase.
- [x] Implement `docs/google-inbound-sync-phase.md` after focus/onboarding stabilization.

## Phase 4. Focus And Schedule Connection

- [x] Add schedule-block context to `GET /api/focus/current`.
- [x] Return current or next schedule block from backend using the user's timezone.
- [x] Stop relying on frontend-only fallback for primary focus copy.
- [x] Keep focus actions limited to executable event/task items until schedule-block actions are defined.
- [x] Add backend tests for schedule-only focus state.

## Phase 5. Suggestion Reliability

- [x] Make suggestion payload summaries more frontend-friendly.
- [x] Add apply/reject/revert result details that can be shown directly in UI.
- [x] Keep user-confirmed application UX; do not add auto-apply yet.
- [x] Add tests for partial/no-op suggestion application cases.

## Phase 6. Development-Time Quality Gate

- [x] Add Playwright after focus/dashboard contracts stabilize.
- [x] Cover login/mock-login to onboarding/dashboard routing.
- [x] Cover onboarding completion to dashboard handoff.
- [x] Cover schedule block create, edit, delete.
- [x] Cover focus current display with schedule-only data.
- [ ] Add CI/Docker execution wrapper for backend, frontend, and E2E after Docker packaging starts.

## Current Active Task

- [x] Phase 0: Capture baseline dirty tree and preserve existing uncommitted work before write-back implementation.
- [x] Phase 0: Run baseline backend test, frontend typecheck, and frontend production build.
- [x] Phase 0.5: Add architecture lock for canonical Events/Tasks model, SyncMapping ownership, OAuth capabilities, write outbox, and fork/detach policy.
- [x] Phase 1: Implement Google Calendar/Tasks provider write-back behind approval-first local mutations.
- [x] Phase 2: Migrate AI suggestion execution from ScheduleBlock-only commands to Event/Task operations.
- [x] Phase 3: Expose Google capability and provider-write pending status in shell/dashboard UX.
- [x] Phase 3: Complete detailed weekly/focus/approval conflict-state UX for provider-backed write failures.
- [x] Phase 4: Add mocked Google write-back Playwright flow and visual QA captures.
- [x] Phase 4: Add schedule-block context to focus current API and connect frontend focus/dashboard displays to it.
- [x] Phase 2: Move dashboard-only derived metrics into backend DTOs.
- [x] Phase 5: Make suggestion payload summaries more frontend-friendly.
- [x] Phase 5: Add apply/reject/revert result details that can be shown directly in UI.
- [x] Phase 5: Add tests for partial/no-op suggestion application cases.
- [x] Phase 5: Confirm user-applied suggestion UX stays manual-only.
- [x] Phase 6: Add Playwright and cover login/mock-login to onboarding/dashboard routing.
- [x] Phase 6: Cover onboarding completion to dashboard handoff.
- [x] Phase 6: Cover schedule block create, edit, delete.
- [x] Phase 6: Cover focus current display with schedule-only data.
- [x] Phase 7: Design DB-backed detailed focus preferences once UX is fixed.
- [x] Phase 8: Implement Google Calendar and Tasks inbound sync from `docs/google-inbound-sync-phase.md`.
- [ ] Phase 9: Docker packaging and CI/E2E wrapper when requested.

## Verification Log

- 2026-05-15: `backend/.\\gradlew.bat test` passed before write-back implementation.
- 2026-05-15: `frontend/npm run typecheck` passed before write-back implementation.
- 2026-05-15: `frontend/npm run build` passed before write-back implementation.
- 2026-05-15: Added `docs/architecture-lock-2026-05-15.md` as Phase 0.5 gate for Google write-back implementation.
- 2026-05-15: `backend/.\\gradlew.bat test --tests com.timetable.operator.agent.api.AgentControllerTest` passed after canonical Event/Task suggestion apply tests.
- 2026-05-15: `backend/.\\gradlew.bat test` passed after provider write-back foundation and canonical Event/Task suggestion apply changes.
- 2026-05-15: `frontend/npm run typecheck` passed after Google capability/write-state UI fields.
- 2026-05-15: `frontend/npm run build` passed after Google capability/write-state UI fields.
- 2026-05-15: `frontend/npm run e2e` passed 4/4 after making the focus E2E schedule seed reuse an overlapping default block instead of failing on overlap.
- 2026-05-15: `backend/.\\gradlew.bat test --tests com.timetable.operator.sync.api.SyncControllerTest` passed after adding mocked outbound provider write flush coverage.
- 2026-05-15: `backend/.\\gradlew.bat test` passed after local mock Google sync/write-back and provider-write status meta refinements.
- 2026-05-15: `frontend/npm run typecheck` passed after mocked Google write-back E2E and provider-write status copy refinements.
- 2026-05-15: `frontend/npm run build` passed after mocked Google write-back E2E and provider-write status copy refinements.
- 2026-05-15: `frontend/npm run e2e` passed 6/6 with backend booted using `--app.sync.polling.enabled=false --app.sync.google.mock-enabled=true`; new flows cover mock Google connection, inbound Calendar/Tasks sync, local Event provider-write outbox, outbound flush, synced external id, dashboard pending/ready screenshots, and core visual QA captures.
- 2026-04-27: `backend/.\\gradlew.bat test` passed.
- 2026-04-27: `frontend/npm run typecheck` passed.
- 2026-04-27: `frontend/npm run build` passed.
- 2026-04-27: `frontend/npx playwright test --list` found 4 Chromium E2E specs.
- Live browser E2E requires the running backend to be restarted after the Mock login identity-isolation change.
- 2026-05-02: `backend/.\\gradlew.bat test` passed after DB-backed focus preference and Google Calendar/Tasks inbound sync changes.
- 2026-05-02: `frontend/npm run typecheck` passed after focus/sync contract changes.
- 2026-05-02: `frontend/npm run build` passed after focus/sync contract changes.
- 2026-05-02: `frontend/npx playwright test --list` found 4 Chromium E2E specs.
- Running servers must be restarted before live browser E2E because `V8__add_focus_preference_details.sql` adds new DB columns and the sync service now uses the read-only Google inbound client.
