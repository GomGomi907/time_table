# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-06-03
- Primary product surfaces: login, onboarding, dashboard/today briefing, scalable timeline calendar, weekly planner, focus mode, AI adjustment review, Google sync trust states.
- Evidence reviewed:
  - `docs/frontend-backend-gap-memo.md`
  - `docs/visual-qa-2026-05-15.md`
  - `frontend/components/dashboard-view.tsx`
  - `frontend/components/focus-view.tsx`
  - `frontend/components/focus-rail-card.tsx`
  - `frontend/components/schedule-view.tsx`
  - `frontend/hooks/use-calendar-range-query.ts`
  - `frontend/lib/api.ts`
  - `frontend/lib/types.ts`
  - `frontend/components/app-shell.tsx`
  - `frontend/app/globals.css`
  - `backend/src/main/java/com/timetable/operator/calendar/api/CalendarController.java`
  - `backend/src/main/java/com/timetable/operator/calendar/application/CalendarRangeService.java`
  - `backend/src/main/java/com/timetable/operator/events/infrastructure/EventRepository.java`
  - `backend/src/main/java/com/timetable/operator/tasks/infrastructure/TaskRepository.java`
  - `backend/src/main/java/com/timetable/operator/schedule/infrastructure/ScheduleBlockRepository.java`
  - `.omx/reports/qa-devtools-final-dashboard-snapshot.txt`
  - `.omx/reports/visual-before-dashboard-20260516.png`
  - `.omx/reports/visual-after-dashboard-20260516.png`
  - `.omx/reports/visual-after-schedule-20260516.png`
  - `.omx/reports/visual-after-focus-20260516.png`

## V3 north star: Omni-Scale Scheduler
- V3 vision: this product is not a weekly planner with AI decoration. It is an AI time operator that combines a **5-minute atomic snap grid** with multi-resolution calendar views: day, week, month, agenda, and eventually year.
- The assistant must operate as both microscope and telescope:
  - **Microscope**: exact 5-minute placement, drift detection, empty-slot hunting, conflict prevention, and focus-mode recovery.
  - **Telescope**: monthly deadlines, long-range project pressure, energy/category balance, provider sync risk, and future commitments.
- Macro and micro are one system. A month cell, agenda row, week card, day rail, and focus block are different renderings of the same time authority graph, not separate products.
- V3 design must prefer proactive drafts over passive explanation: when sync conflicts, execution drift, missed starts, early completions, or deadline pressure are detected, the assistant should generate a reviewable draft plan without waiting for the user to beg for help.
- V3 trust boundary: proactive draft generation is allowed; silent persistence mutation is not. Google write-back and destructive/local schedule mutations remain approval-gated unless a future policy explicitly grants bounded auto-apply.

## Brand
- Personality: calm, decisive, high-trust personal operations assistant; more proactive chief of staff than generic calendar.
- Trust signals: proactively detects drift/conflicts/risk, explains why time should move, distinguishes local vs Google state, shows protected goals, keeps undo/reject paths visible, and never confuses a draft with an applied change.
- Avoid: generic dashboard cards, vague “AI magic” copy, decorative gradients that make the product feel like a demo, hiding sync/write-back uncertainty.

## Product goals
- Goals:
  - Help users know what to do now, what is at risk today, and what the AI recommends changing.
  - Make the product feel like a Reclaim-style AI scheduler operated by a personal assistant.
  - Turn schedule data, goals, focus state, and Google sync into an actionable daily briefing.
  - Coordinate time at 5-minute atomic precision while preserving monthly/yearly strategic awareness.
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
  - “When I slip, detect the drift, draft the cascade, and explain why.”
  - “Show me whether Google is actually in sync.”
- Key contexts of use:
  - Morning planning, mid-day drift recovery, pre-meeting focus decisions, evening goal rescue.

## Information architecture
- Primary navigation: Dashboard/Today Briefing, Timeline, Focus Mode.
- Core routes/screens:
  - `/dashboard`: command-center briefing and AI adjustment review.
  - `/schedule`: scalable timeline workspace that can switch between month, week, day/5-minute orchestration, and agenda stream without changing the trust model.
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
- Principle 5: Scalable timeline. The assistant must act as both microscope and telescope: month/year/list surfaces expose macro risk and energy allocation, while week/day/focus surfaces preserve 5-minute control.
- Principle 6: Real-time drift adaptation. Execution deltas must flow back into the timeline: late starts, early finishes, overruns, missed blocks, and provider changes generate cascade-aware draft adjustments instead of leaving stale plans on screen.
- Tradeoffs: keep existing backend contracts where possible; prefer frontend synthesis over broad API reshaping for this iteration. Do not add virtualization or motion dependencies until measured range size or visual continuity defects prove existing React/CSS/Query primitives insufficient.

## Visual language
- Color: warm off-white foundation with restrained violet accents; violet signals AI authority and primary action only, not every surface. Green is reserved for healthy/synced/goal-preserving states, amber/red only for real risk.
- Typography: editorial briefing headline, but sized to keep Korean phrases readable without awkward single-word wrapping. Compact supporting rationale, Korean body copy no smaller than readable 14px-equivalent.
- Spacing/layout rhythm: briefing first, then two-column operational context; avoid full-screen hero bloat and uniform card soup.
- Shape/radius/elevation: retain rounded product language with thin borders and soft shadows; avoid heavy glow. Use elevation to mark active decision surfaces.
- Motion: subtle transitions only; avoid distracting “AI demo” motion.
- Motion for timeline zoom: use CSS/React state transitions and shared visual anchors first; never require animation to understand navigation. Respect reduced motion.
- Imagery/iconography: text-first; badges/icons only when they clarify protected, flexible, risk, or sync state.

## Components
- Existing components to reuse:
  - `AppShell`, `SectionHeader`, `FocusActionBar`, `FocusRailCard`, notice store, API layer.
- New/changed components:
  - Dashboard-level Today Briefing hero.
  - AI judgment/adjustment preview panel.
  - Protected goal and risk chips.
  - Secretary log/trust summary.
- Timeline view switcher for `month`, `week`, `day`, and `agenda`.
- Monthly mosaic day cell with compact AI summary badges.
- Agenda stream row combining calendar events, tasks, and routine occurrences.
- Timeline zoom anchor/skeleton handoff between macro and micro views.
- Elastic time-rail for day/week micro-control: visible time anchors plus a 5-minute atomic snap grid without regressing to a rigid table.
- Variants and states:
  - No active item, active focus, pending AI suggestion, read-only Google, write-back pending, conflicts, reconnect required.
- Token/component ownership:
  - `frontend/app/globals.css` owns current tokens and component classes.
  - Avoid adding Tailwind or new UI libraries.

## Accessibility
- Target standard: pragmatic WCAG 2.1 AA for contrast, keyboard access, semantic headings, and actionable labels.
- Keyboard/focus behavior: primary AI actions must be real buttons/links and visible in tab order.
- Contrast/readability: avoid low-contrast lavender text; preserve high contrast for briefing copy.
- Screen-reader semantics: maintain headings/sections; do not encode critical assistant rationale only in visuals.
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
- Use “오늘 브리핑”, “AI 비서 메모”, “지켜둘 항목”, “조정안”, “반영 상태”, “타임라인”, “5분 조율”.
  - Avoid “대시보드 카드”, “작전 지도”, “fallback”, “payload” in user-facing copy.
- Microcopy rules:
  - Say what the assistant noticed, what it will protect, and what tradeoff it chose.
  - Prefer active next steps over generic status labels.

## Implementation constraints
- Framework/styling system: Next.js App Router, React 19, CSS in `frontend/app/globals.css`.
- Design-token constraints: extend current CSS variables/classes; no new dependency.
- Performance constraints: keep dashboard synthesis client-side and cheap; no heavy libraries. Timeline must use bounded range queries, React Query placeholder/prefetch behavior, and CSS-efficient rendering before adding external virtualization/motion packages.
- Compatibility constraints: current backend dashboard/focus/sync/suggestion APIs remain source of truth.
- Test/screenshot expectations: `npm run typecheck`, `npm run build`, targeted frontend visual/browser smoke, and backend tests if API-contract files change.

## Open questions
- [ ] Should AI eventually auto-apply low-risk moves, or always require approval? Owner: product. Impact: trust model and write-back UX.
- [ ] What is the exact brand reference set? Owner: product. Impact: visual refinement.
- [ ] Which goals are “protected” by policy vs inferred from priority/progress? Owner: product/backend. Impact: assistant reasoning quality.
- [ ] Should `/schedule` default to month once Monthly Mosaic exists, or keep week as the execution-first default? Owner: product. Impact: navigation and first impression.
- [ ] What is the first supported year-level contract: heatmap-only summary API, paged month windows, or no year view until real recurrence expansion is proven? Owner: product/backend. Impact: backend range and performance budget.
- [ ] Should drag-to-schedule in Agenda Stream create local routine blocks, local events, or task scheduling commands? Owner: product/backend. Impact: mutation semantics and Google write-back trust.

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

## Weekly planner redesign — 2026-05-17, V3 reinterpretation 2026-06-03
- Evidence artifacts:
  - `.omx/screenshots/weekly-schedule-analysis/desktop-1440-03-week-grid.png`
  - `.omx/screenshots/weekly-schedule-analysis/tablet-1024-03-week-grid.png`
  - `.omx/screenshots/weekly-schedule-analysis/mobile-390-06-mobile-agenda.png`
  - `.omx/reports/weekly-schedule-redesign-audit-20260517.json`
- Design decision: the weekly planner should not present a raw 00:00–24:00 rigid table. In V3 it becomes an **Elastic Time-rail**: a visible time axis with 5-minute atomic snap guides in the background, focused on the active operating range around real events. Cards remain readable and flexible, but placement math and editing semantics snap to 5-minute increments.
- Layout rules:
  - Desktop week timeline fits all seven days inside the main content width where possible.
  - Tablet keeps horizontal scroll with an explicit affordance instead of squeezing unreadable columns.
  - Mobile uses `mobile-week-agenda` list cards rather than a tiny rigid calendar table.
  - Adjacent events stack by time; only true visual collisions split into columns.
- Card rules:
  - Dense 5-minute-snap blocks show the title only when there is enough space; full time remains in the accessible label and edit modal.
  - Standard blocks show title and time; notes stay out of dense grid cells.
  - Category is expressed by restrained left accent + soft surface, not full-cell saturation.
- Quality gate: desktop/tablet `schedule-block-overlap=0`, `schedule-block-clipped=0`, `small-click-target=0` in the 2026-05-17 audit.

## Weekly planner sleep-boundary rule — 2026-05-17, V3 reinterpretation 2026-06-03
- Decision: `SLEEP` blocks are not rendered as schedule cards in the weekly planner or mobile weekly agenda. They define the visible daily operating window instead.
- Time range rule:
  - Prefer sleep/wake boundaries when present.
  - Start the visible axis at the earliest detected wake boundary, snapped to the hour for readable ticks.
  - End the visible axis at bedtime, including next-day labels such as `다음 01:00` when bedtime is after midnight.
  - If a non-sleep commitment exists outside that window, expand the range so user-created commitments are never silently hidden.
- Density rule: use a tighter hour scale than the previous redesign while preserving usable hit targets for 5-minute atomic snap placement. Very short blocks may render as compact rails/markers with accessible labels instead of full text cards.
- Content rule: sleep remains part of AI scheduling context and suggestions, but it should not visually consume planner space.

## Scalable Timeline Architecture — 2026-06-03
- Product decision: `/schedule` evolves from a week-only planner into an omni-scale timeline. The surface must support macro planning and micro control as one continuous mental model: month/year/list views reveal long-range risk, while week/day/focus views preserve 5-minute orchestration.
- Evidence:
  - Backend already exposes `GET /api/calendar/range?start=...&end=...&view=...&timezone=...` through `CalendarController`.
  - `CalendarRangeService` already accepts `DAY`, `WEEK`, `MONTH`, and `AGENDA`, enforces a bounded range, merges events/tasks/routine occurrences, projects routine blocks synthetically, and returns instrumentation.
  - Frontend already has `useCalendarRangeQuery`, `calendarRangeQueryRootKey`, `CalendarRangeResponse`, and optimistic range-cache update helpers inside `schedule-view.tsx`.
  - Current visible UI remains week-stack dominant, so month/agenda/year are product gaps, not backend impossibilities.

### ADR: Range API Spine and zoom hydration
- **Decision**: all timeline read models must flow through `GET /api/calendar/range` until instrumentation proves the contract insufficient. Do not create fragmented `/calendar/month`, `/calendar/agenda`, `/calendar/week-summary`, or route-specific read endpoints for the first V3 slice.
- **Drivers**: consistent authority, fewer cache invalidation paths, shared optimistic updates, and one place to prove range query performance.
- **Hydration strategy**:
  - month/agenda views request viewport-bounded metadata through the range spine;
  - hover/focus/near-scroll prefetches adjacent ranges with React Query;
  - zoom from month/agenda into day/week keeps selected date visible while full day/week payload hydrates;
  - skeletons must describe what is loading, not hide orientation.
- **Rejected**: one endpoint per visual mode. It would make the UI appear faster in the short term but would fracture cache invalidation, AI context, and sync-trust semantics.
- **Consequence**: any future summary/heatmap endpoint, especially for year view, needs its own PRD and must explain why `/api/calendar/range` cannot serve it safely.

### ADR: Time authority hierarchy
- **Decision**: timeline rendering and AI orchestration follow a clear authority stack:
  1. **Provider fixed commitments**: Google Calendar imported events with remote authority block lower-priority local/routine suggestions unless explicitly forked or detached.
  2. **Local protected commitments**: protected routines, sanctuary blocks, approved local events, and user-pinned focus windows resist automatic movement.
  3. **Goal-linked work**: tasks/events tied to active goals influence macro risk and can receive protected suggestions when deadlines approach.
  4. **Flexible local routines/tasks**: can be moved by draft suggestions and 5-minute slot hunting.
  5. **AI draft proposals**: may be proactive, but remain drafts until approved or covered by a future bounded auto-apply policy.
- **Google vs local rule**: Google is not always “more important,” but it is the external truth for imported fixed commitments. Local routine is the service truth for user rhythm. When they collide, the UI must show the authority conflict and the assistant must propose a draft, not silently choose.
- **Consequence**: AI copy must name the authority conflict in user language: e.g. `Google 일정과 보호된 루틴이 겹쳐 조정안을 만들었습니다.`

### Drift engine architecture
- **Decision**: focus/execution mode must report execution deltas back into scheduling context as first-class drift signals.
- **Drift inputs**:
  - started late or early,
  - finished early,
  - overran,
  - skipped/missed,
  - provider sync inserted or removed a commitment,
  - user manually moved a block,
  - rejected/applied AI suggestion changed the local plan.
- **Delta model**:
  - compare planned start/end against actual or observed state;
  - snap proposed repairs to the 5-minute atomic grid;
  - classify blast radius: same block, same day, week, visible month;
  - produce a cascade draft with affected items, protected items, and unresolved conflicts.
- **Output**: the engine creates reviewable AI suggestions/drafts. It does not directly mutate Google or local protected commitments without approval.
- **UI obligation**: show `무엇이 밀렸는가`, `무엇을 지키는가`, `무엇을 옮기려는가`, `승인 전에는 반영되지 않음`.

### Timeline information architecture
- `/schedule` owns a single timeline workspace with a segmented view switcher:
  1. **Month / Monthly Mosaic**: primary macro planning view. Traditional month grid, but each day cell shows density and AI summary badges, not dense schedule text.
  2. **Week / Weekly Planner**: current operating map. It remains the default execution planning view until month/day adoption is proven.
  3. **Day / 5-minute Orchestration**: zoomed micro-control view. It is where exact placement, conflicts, and short-slot scheduling happen.
  4. **Agenda / Agenda Stream**: chronological infinite-like list for events, tasks, and routine occurrences, optimized for search/review and drag-to-schedule later.
  5. **Year / Yearly Landscape**: future analytical layer, not first implementation. It summarizes category allocation and risk as heatmap/coverage, without loading full payload for 365 days at once.
- View switches must not change the user's trust model: AI suggestions remain approval-gated, Google write-back remains explicit, and sync uncertainty is still visible as trust state.
- Precision rule: all editable local placement and AI repair drafts snap to the 5-minute atomic grid, even when the current visual layer summarizes at day/month/year density.
- Hybrid timeline rule: reject rigid full-day tables as the primary experience, but keep visible time anchors. The day/week UI is an Elastic Time-rail: cards flow in readable stacks while background guides, drag handles, keyboard nudges, and collision math retain 5-minute precision.

### Multi-resolution data contract
- Keep `/api/calendar/range` as the spine. Do not create one-off `/month`, `/agenda`, or `/year` endpoints until range instrumentation proves the generic contract insufficient.
- Current first-class range views:
  - `MONTH`: fetch visible month grid plus leading/trailing days needed by the calendar viewport, bounded by backend max range.
  - `WEEK`: existing planning density and mutation cache behavior.
  - `DAY`: micro-placement and 5-minute AI orchestration.
  - `AGENDA`: same occurrence payload sorted chronologically, rendered as stream.
- `YEAR` must not initially fetch 365 days of full occurrences through the same payload. If built, it should use a separate summary/heatmap contract or paged month windows after a backend PRD.
- Monthly cells use derived frontend summaries from `CalendarOccurrence[]` first:
  - count by entity type,
  - total scheduled/focus/protected minutes,
  - risk markers from due tasks, protected windows, sync state, or pending suggestions,
  - short Korean badge copy such as `미팅 4개`, `집중 3시간`, `마감 임박`.
- Avoid returning LLM-written day summaries in the first pass. AI copy must be backed by visible state; deterministic summary badges are safer and cheaper.

### Range Query Engine 2.0 rules
- Existing range service is the correct foundation, but the next backend pass must prove:
  - one user-scoped range read per entity group, not per day;
  - no N+1 goal/task/event loading when month windows include linked goals;
  - routine/synthetic occurrences are generated in memory, not persisted as thousands of rows;
  - query instrumentation remains exposed in tests or diagnostics so regressions are visible.
- Current `CalendarRangeService.QueryInstrumentation` already reports repository groups, occurrence count, and range days; keep or extend this rather than hiding query behavior.
- Monthly range hard limit must stay bounded. A month viewport is acceptable under the current 45-day limit; yearly full-payload fetch is not.
- Recurring provider events with RRULE must be designed before broad expansion. Current occurrence payload flags recurrence but does not yet prove full virtual RRULE expansion for provider series.

### Dimensional zoom interaction
- Macro-to-micro navigation must preserve orientation:
  - Month day cell click/tap selects the date and opens day/week context with a visible date header.
  - Double-tap/Enter on a month day cell zooms to day view.
  - Shift or explicit command can open week view anchored on that date.
  - Agenda row action opens the exact day/time in micro planner.
- Use shared visual anchors before external motion libraries:
  - selected date ring,
  - persistent month/day breadcrumb,
  - skeleton day panel while full range/day payload hydrates,
  - React Query prefetch for adjacent month/week/day ranges on hover/focus/near-scroll.
- Motion path must be progressive enhancement only. Reduced-motion users get instant state transition with the same breadcrumb and selected-date context.

### Frontend dependency policy
- Do not add `react-virtualized`, Framer Motion, or another motion/virtualization dependency as a first move.
- First pass must use:
  - existing React 19 / Next.js / TanStack Query stack,
  - CSS grid for month,
  - bounded range windows,
  - `placeholderData`,
  - prefetch/invalidation via `calendarRangeQueryRootKey`,
  - simple windowing only if implemented locally and testably.
- External virtualization becomes acceptable only after evidence:
  - month/agenda rendering drops below target responsiveness on representative data,
  - local windowing is more complex/risky than the dependency,
  - bundle and accessibility tradeoffs are documented.

### Macro-micro AI behavior
- AI context should include both:
  - micro availability: near-term empty slots and active conflicts,
  - macro risk: upcoming due tasks/events/goals from the visible or relevant future range.
- The assistant may defend present time using future risk only when that risk is explicit in state. Example: “다음 주 목요일 마감이 있어 지금 25분만 확보하는 편이 안전합니다.”
- AI must not fabricate yearly/monthly insight from unloaded ranges. If macro range is not loaded, copy must say it needs that range or request a range fetch.
- Rejected AI suggestions remain part of memory/context policy as already captured in AI hardening plans; macro planning must not repeat rejected long-range moves.

### First implementation slice
1. Add route-local timeline view state to `/schedule`: `month | week | day | agenda`, with week as current default unless product chooses month default later.
2. Build `MonthlyMosaic` from `useCalendarRangeQuery({ view: "month" })` and deterministic day summaries.
3. Build `AgendaStream` from the same occurrence payload sorted by `startAt`, grouped by local date.
4. Add date selection handoff from month/agenda into week/day anchors.
5. Keep existing week stack and schedule mutation flows intact; range cache invalidation already exists and must continue to update all visible range queries.
6. Add tests for month summary, agenda chronological grouping, view switch orientation, and bounded range requests.

### Non-goals for the first slice
- No full yearly infinite landscape with 365-day full occurrence fetch.
- No drag-and-drop scheduling until day/week anchors and mutation semantics are stable.
- No external virtualization/motion dependency without measured evidence.
- No hidden AI auto-application.
- No provider RRULE virtual expansion claim beyond what backend tests prove.

### Acceptance gate
- Month view renders at least current/next/previous visible month windows without visual overflow, Korean clipping, or unreadable day cells.
- Agenda stream preserves chronological order across events, tasks, and routine occurrences.
- Switching month → day/week keeps selected date visible and understandable without relying on animation.
- Backend range tests prove bounded query groups for month windows and no day-by-day N+1 behavior.
- Frontend typecheck, hygiene, and targeted E2E pass.
- If deployment is performed, deployed revision must be verified through authenticated `gcli`/`gcloud` build/deploy records before calling it live.

## Frontend productization screen contracts — 2026-05-17
- Primary productization goal: every route must have one job, one primary question, and one dominant action. This is the guardrail against AI-slop screen composition.
- Screen roles:
  - `/login`: prove value and trust boundary before Google connection. Primary question: “내 캘린더/할 일을 연결해도 안전하고 유용한가?”
  - `/onboarding`: collect only the lifestyle rhythm AI cannot infer. Primary question: “최소 입력으로 일정 조정 기준을 정할 수 있는가?”
  - `/dashboard`: today command center. Primary question: “오늘 지금 무엇을 확인하고 실행해야 하나?”
  - `/schedule`: multi-resolution timeline workspace. Primary question: “오늘부터 장기 일정까지 시간 배치가 현실적인가?”
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
  4. Main next action is singular: pending suggestion -> 조정안 검토, executable current/recommended item -> 실행 모드 시작, otherwise -> 타임라인 보기.
  5. Google sync detail, generic statistics, and focus mini-screen/timer controls are not primary briefing content. They may appear only as quiet lower context or in their dedicated route.
- Route boundary: keep `/dashboard` as 오늘 브리핑, `/schedule` as multi-resolution timeline planning/adjustment review, and `/focus` as low-noise execution. Post-onboarding landing changes remain out of scope without explicit approval.
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
