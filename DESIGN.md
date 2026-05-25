# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-05-16
- Primary product surfaces: login, onboarding, dashboard/today briefing, weekly planner, focus mode, AI adjustment review, Google sync trust states.
- Evidence reviewed:
  - `docs/frontend-backend-gap-memo.md`
  - `docs/visual-qa-2026-05-15.md`
  - `frontend/components/dashboard-view.tsx`
  - `frontend/components/focus-view.tsx`
  - `frontend/components/focus-rail-card.tsx`
  - `frontend/components/app-shell.tsx`
  - `frontend/app/globals.css`
  - `.omx/reports/qa-devtools-final-dashboard-snapshot.txt`\n  - `.omx/reports/visual-before-dashboard-20260516.png`\n  - `.omx/reports/visual-after-dashboard-20260516.png`\n  - `.omx/reports/visual-after-schedule-20260516.png`\n  - `.omx/reports/visual-after-focus-20260516.png`

## Brand
- Personality: calm, decisive, high-trust personal operations assistant; more “chief of staff” than generic calendar.
- Trust signals: explains why time is moved, distinguishes local vs Google state, shows protected goals, keeps undo/reject paths visible, avoids overclaiming automation.
- Avoid: generic dashboard cards, vague “AI magic” copy, decorative gradients that make the product feel like a demo, hiding sync/write-back uncertainty.

## Product goals
- Goals:
  - Help users know what to do now, what is at risk today, and what the AI recommends changing.
  - Make the product feel like a Reclaim-style AI scheduler operated by a personal assistant.
  - Turn schedule data, goals, focus state, and Google sync into an actionable daily briefing.
- Non-goals:
  - Pixel-perfect clone of Reclaim, Motion, or any external app.
  - Fully autonomous production write-back without explicit trust/approval states.
  - Adding a new design-system dependency before existing repo-native components are exhausted.
- Success signals:
  - First screen answers “what should I do now?”
  - AI suggestions read as decisions with rationale and tradeoffs, not generic cards.
  - Users can distinguish protected time, flexible time, risk, and Google write-back status.

## Personas and jobs
- Primary personas:
  - Busy knowledge worker balancing meetings, deep work, life routines, and goals.
  - Solo builder/student who needs a personal time operator rather than another task list.
- User jobs:
  - “Brief me on my day.”
  - “Protect my important work and goals.”
  - “When I slip, decide what moves and explain why.”
  - “Show me whether Google is actually in sync.”
- Key contexts of use:
  - Morning planning, mid-day drift recovery, pre-meeting focus decisions, evening goal rescue.

## Information architecture
- Primary navigation: Dashboard/Today Briefing, Weekly Planner, Focus Mode.
- Core routes/screens:
  - `/dashboard`: command-center briefing and AI adjustment review.
  - `/schedule`: visual operating map for the week.
  - `/focus`: low-noise execution mode.
  - `/onboarding`: personal rhythm calibration and first AI proposal.
- Content hierarchy:
  1. AI daily briefing and next decision.
  2. Current/next execution block.
  3. Risks, protected goals, and adjustment preview.
  4. Full schedule, goals, sync details.

## Design principles
- Principle 1: Brief before dashboard. The first screen should interpret state, not merely list state.
- Principle 2: Explain before applying. Every AI change needs rationale, protected objective, and visible trust state.
- Principle 3: Reduce user computation. Replace raw counts/statuses with plain-language decisions.
- Principle 4: Calm authority. Use fewer but stronger visual elements; let hierarchy carry confidence.
- Tradeoffs: keep existing backend contracts where possible; prefer frontend synthesis over broad API reshaping for this iteration.

## Visual language
- Color: warm off-white foundation with restrained violet accents; violet signals AI authority and primary action only, not every surface. Green is reserved for healthy/synced/goal-preserving states, amber/red only for real risk.
- Typography: editorial briefing headline, but sized to keep Korean phrases readable without awkward single-word wrapping. Compact supporting rationale, Korean body copy no smaller than readable 14px-equivalent.
- Spacing/layout rhythm: briefing first, then two-column operational context; avoid full-screen hero bloat and uniform card soup.
- Shape/radius/elevation: retain rounded product language with thin borders and soft shadows; avoid heavy glow. Use elevation to mark active decision surfaces.
- Motion: subtle transitions only; avoid distracting “AI demo” motion.
- Imagery/iconography: text-first; badges/icons only when they clarify protected, flexible, risk, or sync state.

## Components
- Existing components to reuse:
  - `AppShell`, `SectionHeader`, `FocusActionBar`, `FocusRailCard`, notice store, API layer.
- New/changed components:
  - Dashboard-level Today Briefing hero.
  - AI judgment/adjustment preview panel.
  - Protected goal and risk chips.
  - Secretary log/trust summary.
- Variants and states:
  - No active item, active focus, pending AI suggestion, read-only Google, write-back pending, conflicts, reconnect required.
- Token/component ownership:
  - `frontend/app/globals.css` owns current tokens and component classes.
  - Avoid adding Tailwind or new UI libraries.

## Accessibility
- Target standard: pragmatic WCAG 2.1 AA for contrast, keyboard access, semantic headings, and actionable labels.
- Keyboard/focus behavior: primary AI actions must be real buttons/links and visible in tab order.
- Contrast/readability: avoid low-contrast lavender text; preserve high contrast for briefing copy.
- Screen-reader semantics: maintain headings/sections; do not encode critical AI reasoning only in visuals.
- Reduced motion and sensory considerations: no required animation for understanding.

## Responsive behavior
- Supported breakpoints/devices: desktop command center, tablet stacked layout, mobile vertical briefing-first flow.
- Layout adaptations: briefing hero stacks above cards under 1160px; week grid remains horizontally scrollable.
- Touch/hover differences: schedule blocks and action buttons must retain large hit targets.

## Interaction states
- Loading: describe what the assistant is preparing.
- Empty: explain what is missing and what the user can do next.
- Error: plain recovery path, not raw implementation detail.
- Success: confirm what changed and whether Google write-back is pending.
- Disabled: explain pending action or missing prerequisite.
- Offline/slow network: keep local explanation visible; do not imply Google has been updated.

## Content voice
- Tone: concise, executive, Korean-first, confident but not authoritarian.
- Terminology:
  - Use “오늘 브리핑”, “AI 비서 메모”, “지켜둘 항목”, “조정안”, “반영 상태”, “주간 일정”.
  - Avoid “대시보드 카드”, “작전 지도”, “fallback”, “payload” in user-facing copy.
- Microcopy rules:
  - Say what the assistant noticed, what it will protect, and what tradeoff it chose.
  - Prefer active next steps over generic status labels.

## Implementation constraints
- Framework/styling system: Next.js App Router, React 19, CSS in `frontend/app/globals.css`.
- Design-token constraints: extend current CSS variables/classes; no new dependency.
- Performance constraints: keep dashboard synthesis client-side and cheap; no heavy libraries.
- Compatibility constraints: current backend dashboard/focus/sync/suggestion APIs remain source of truth.
- Test/screenshot expectations: `npm run typecheck`, `npm run build`, targeted frontend visual/browser smoke, and backend tests if API-contract files change.

## Open questions
- [ ] Should AI eventually auto-apply low-risk moves, or always require approval? Owner: product. Impact: trust model and write-back UX.
- [ ] What is the exact brand reference set? Owner: product. Impact: visual refinement.
- [ ] Which goals are “protected” by policy vs inferred from priority/progress? Owner: product/backend. Impact: assistant reasoning quality.

## Visual QA refresh — 2026-05-16 Apple-grade text polish
- Evidence artifacts:
  - `.omx/reports/apple-polish/01-login.png`
  - `.omx/reports/apple-polish/02-onboarding-start.png`
  - `.omx/reports/apple-polish/03-onboarding-current.png`
  - `.omx/reports/apple-polish/04-dashboard.png`
  - `.omx/reports/apple-polish/05-schedule.png`
  - `.omx/reports/apple-polish/06-focus.png`
  - `.omx/reports/apple-polish/*-text-metrics.json`
  - `.omx/state/apple-grade-visual-polish-ralph-progress.json`
- Visual verdict: PASS, score 96/100, threshold 95, viewport 1440x1000.
- Metric gate: 6/6 captured screens report `issueCount: 0` for viewport overflow, oversized headings, Korean readable text below threshold, narrow long labels, and important labels wrapping 4+ lines.
- Text/layout decisions:
  - Login headline must wrap inside the left panel and never collide with the Google action card.
  - Onboarding sidebar copy should stay short and executive; option-card text is measured by child label/description, not by aggregate button text.
  - Schedule blocks show title/time only in the grid; long notes belong in detail/adjacent cards, not dense calendar cells.
  - Korean product copy must avoid raw provider/demo labels such as “Mock Google 태스크”; normalize to service-native wording like “오늘 할 일 정리”.
- Follow-up design risk: this pass is desktop-command-center focused. Run the same capture gate for tablet/mobile before calling responsive design complete.

## Weekly planner redesign — 2026-05-17
- Evidence artifacts:
  - `.omx/screenshots/weekly-schedule-analysis/desktop-1440-03-week-grid.png`
  - `.omx/screenshots/weekly-schedule-analysis/tablet-1024-03-week-grid.png`
  - `.omx/screenshots/weekly-schedule-analysis/mobile-390-06-mobile-agenda.png`
  - `.omx/reports/weekly-schedule-redesign-audit-20260517.json`
- Design decision: the weekly planner should not present a raw 00:00–24:00 wall of empty grid. It should focus on the active operating range around real events, with a clear week summary, full Korean weekday labels, and service-grade cards that read as planned commitments.
- Layout rules:
  - Desktop week grid fits all seven days inside the main content width where possible.
  - Tablet keeps horizontal scroll with an explicit affordance instead of squeezing unreadable columns.
  - Mobile uses `mobile-week-agenda` list cards rather than a tiny calendar grid.
  - Adjacent events stack by time; only true visual collisions split into columns.
- Card rules:
  - Dense 30-minute blocks show the title only in-grid; full time remains in the accessible label and edit modal.
  - Standard blocks show title and time; notes stay out of dense grid cells.
  - Category is expressed by restrained left accent + soft surface, not full-cell saturation.
- Quality gate: desktop/tablet `schedule-block-overlap=0`, `schedule-block-clipped=0`, `small-click-target=0` in the 2026-05-17 audit.

## Weekly planner sleep-boundary rule — 2026-05-17
- Decision: `SLEEP` blocks are not rendered as schedule cards in the weekly planner or mobile weekly agenda. They define the visible daily operating window instead.
- Time range rule:
  - Prefer sleep/wake boundaries when present.
  - Start the visible axis at the earliest detected wake boundary, snapped to the hour for readable ticks.
  - End the visible axis at bedtime, including next-day labels such as `다음 01:00` when bedtime is after midnight.
  - If a non-sleep commitment exists outside that window, expand the range so user-created commitments are never silently hidden.
- Density rule: use a tighter hour scale than the previous redesign while keeping 30-minute cards at a usable hit target.
- Content rule: sleep remains part of AI reasoning and suggestions, but it should not visually consume planner space.

## Frontend productization screen contracts — 2026-05-17
- Primary productization goal: every route must have one job, one primary question, and one dominant action. This is the guardrail against AI-slop screen composition.
- Screen roles:
  - `/login`: prove value and trust boundary before Google connection. Primary question: “내 캘린더/할 일을 연결해도 안전하고 유용한가?”
  - `/onboarding`: collect only the lifestyle rhythm AI cannot infer. Primary question: “최소 입력으로 일정 조정 기준을 정할 수 있는가?”
  - `/dashboard`: today command center. Primary question: “오늘 지금 무엇을 확인하고 실행해야 하나?”
  - `/schedule`: weekly planning workspace. Primary question: “이번 주 시간 배치가 현실적인가?”
  - `/focus`: single-task execution mode. Primary question: “지금 하나만 한다면 무엇인가?”
- State matrix rule: loading, error, empty, real-data, pending suggestion, and sync-degraded states must share the same product language: current state, why it matters, next available action.
- Copy rule: assistant/AI copy is allowed only when backed by visible state such as schedule data, pending suggestion, sync status, or onboarding answers. Do not imply unsupported automation.
- Component boundary rule: use shared primitives for app frame, section headers, notices, cards, buttons, badges, empty states, and status pills. Page-local variants must reuse shared tokens and be justified by interaction model.
- Visual QA gate: final screenshots must show zero critical overlap, clipping, unreadable text, broken CTA, unsupported AI claim, or route-role ambiguity across desktop/laptop/tablet/mobile captures.

## Today Briefing hierarchy reset — 2026-05-18
- Source artifacts:
  - `.omx/plans/prd-today-briefing-user-flow.md`
  - `.omx/plans/test-spec-today-briefing-user-flow.md`
  - `.omx/specs/deep-interview-user-flow-dashboard-role.md`
- Product decision: `/dashboard` is no longer a generic command-center dashboard. It is a daily briefing whose first answer is “오늘 무엇이 예정되어 있는가?”
- Hierarchy rules:
  1. Secretary-style day summary first, but compact: it is the assistant's first sentence, not a large hero banner. Use a small headline plus 오전/오후/저녁 period chips without using vague “flow” language in user-facing Korean copy.
  2. Full chronological today list second, built from `getDailyBlocks()` for the current day rather than `getDashboardFlow()` because the latter is an anchored current/next subset.
  3. Pending AI approval is prominent but secondary: it may sit beside the schedule on desktop, but stacked/tablet/mobile flows must keep the today schedule before the approval card. Approval/reject affordances preserve the rule that AI never applies changes before user approval.
  4. Main next action is singular: pending suggestion -> 조정안 검토, executable current/recommended item -> 실행 모드 시작, otherwise -> 주간 일정 보기.
  5. Google sync detail, generic statistics, and focus mini-screen/timer controls are not primary briefing content. They may appear only as quiet lower context or in their dedicated route.
- Route boundary: keep `/dashboard` as 오늘 브리핑, `/schedule` as weekly planning/adjustment review, and `/focus` as low-noise execution. Post-onboarding landing or route-flow changes remain out of scope without explicit approval.
- Visual QA gate: desktop/tablet/mobile captures must show schedule summary before sync/focus/stat content, no card soup, no Korean clipping, and visible approval/next-action affordance.
## AI Assistant Tone Guide — 2026-05-18
- Canonical copy guide: `docs/ai-assistant-tone-guide.md`.
- Product voice: confirmed schedule first, next action second, concise approval-gated suggestions only when needed.
- User-facing AI copy must avoid vague assistant jargon and must not use `흐름`.

## Today Briefing simplification pass — 2026-05-19
- Evidence artifacts:
  - `.omx/screenshots/service-improvement-qa/mobile-dashboard-briefing-390.png`
  - `.omx/screenshots/service-improvement-qa/mobile-dashboard-pending-approval-390.png`
  - `.omx/reports/visual-ralph-dashboard-simplification/verdict.json`
- Decision: mobile `/dashboard` must start with the day schedule answer immediately after the global navigation. The page-level “AI 일정 비서 / 오늘 브리핑” header is redundant on mobile because the active nav already names the screen.
- Information reduction rules:
  - Show at most four key schedule rows in the briefing list, anchored around the current/next item; move the rest behind “전체 보기”.
  - Do not show generic statistics, focus mini-timer controls, or sync implementation details on the briefing screen.
  - Approval cards may show one or two user-meaningful preview items only. Hide technical diagnostics such as feature flags, endpoint names, provider internals, or “AI disabled” messages.
  - Read-only Google state remains visible only as a plain trust note, never as a primary card competing with the schedule.
- Visual gate: in a 390px mobile capture, the first content card after navigation must answer “오늘 일정은 몇 개인가 / 다음은 무엇인가”, and pending approval must remain below the schedule list.

## Execution mode detail polish — 2026-05-19
- Evidence artifacts:
  - `.omx/screenshots/service-improvement-qa/mobile-focus-390.png`
  - `.omx/screenshots/service-improvement-qa/desktop-focus-1440.png`
  - `.omx/reports/visual-ralph-focus-detail-polish/verdict.json`
- Decision: `/focus` must never show placeholder time text as the primary timer value. If no active event/task exists, derive the visible timer from the schedule context or recommended task.
- Detail rules:
  - Active event/task: label `남은 시간`, value = item remaining minutes.
  - Recommended task: label `추천 집중`, value = estimated minutes.
  - Schedule-only active block: label `남은 시간`, value = minutes until block end.
  - Schedule-only upcoming block: label `다음 일정`, value = minutes until block start.
  - Duplicate recommended tasks with the same normalized title collapse to one row in the side memo.
  - Internal QA/E2E/mock/seed notes are not user-facing schedule notes and must be hidden from execution cards.
- Visual QA gate: focus captures must contain no `계산 중`, no raw test seed notes, no long timestamp-like QA titles, and no duplicated identical recommendation rows.

## Hero and helper copy polish — 2026-05-19
- Evidence artifacts:
  - `.omx/screenshots/service-improvement-qa/desktop-dashboard-briefing-1440.png`
  - `.omx/screenshots/service-improvement-qa/desktop-schedule-1440.png`
  - `.omx/screenshots/service-improvement-qa/desktop-focus-1440.png`
  - `.omx/reports/visual-ralph-copy-guideline-polish/verdict.json`
- Decision: `hero-copy compact` and screen contract chips must behave like a brief personal assistant, not a product marketing banner.
- Copy rules:
  - First sentence states the visible state or question: today's schedule, this week's tight/overlapping time, or the single item to do now.
  - Second sentence states the next action: start execution mode, check a proposal, add a schedule, approve, defer, or complete.
  - Screen contract labels are `확인할 것` and `다음 행동`.
  - Avoid AI self-praise and abstract productivity language. Prefer concrete terms: `일정`, `다음 일정`, `준비할 일`, `겹치는 일정`, `지연 가능성`, `조정안`, `승인`, `보류`.
  - Provider wording is `Google 캘린더와 할 일`; approval guard copy says changes are not applied before approval.
- Visual QA gate: desktop captures must show the top copy as short, action-oriented, and consistent across `/dashboard`, `/schedule`, `/focus`, and `/login`.
