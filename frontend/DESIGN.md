# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-06-03
- Primary product surfaces: login, onboarding, dashboard, scalable timeline calendar, weekly/day orchestration, agenda stream, focus mode.
- Evidence reviewed: `components/*-view.tsx`, `components/app-shell.tsx`, `components/schedule-view.tsx`, `hooks/use-calendar-range-query.ts`, `lib/api.ts`, `lib/types.ts`, `app/globals.css`, root `DESIGN.md`, Playwright screenshots under `test-results/visual-qa-captures-core-local-visual-QA-surfaces-chromium/`, frontend/backend handoff `.omx/handoffs/frontend-backend-ai-agent-next-iteration-20260530.md`.

## V3 frontend north star: Omni-Scale Scheduler
- The frontend is no longer designing a week-only schedule screen. `/schedule` is a multi-resolution timeline workspace backed by the range API spine.
- The UI must combine:
  - **5-minute atomic snap grid** for editable local placement, AI repair drafts, keyboard nudges, and collision math;
  - **Monthly Mosaic** for macro risk and day-density scanning;
  - **Agenda Stream** for chronological review across events, tasks, and routines;
  - **Elastic Time-rail** for week/day micro-control without returning to a rigid full-day table.
- Proactive assistant behavior is a frontend requirement: drift, sync conflicts, missed starts, overruns, early completions, and deadline pressure should surface reviewable draft adjustments. Drafts must be visibly distinct from applied changes.
- Silent mutation remains forbidden. Proactivity means “draft and explain,” not “secretly apply.”

## Brand
- Personality: Toss-like product structure, Apple-like calm tone; precise, quiet, trustworthy, and proactive.
- Trust signals: clear hierarchy, aligned grids, restrained shadows, concise copy, state-backed rationale, explicit draft/applied distinction, and visible Google/local authority.
- Avoid: oversized demo typography, cramped cards, private chain-of-thought, provider/debug metadata, fake reasoning, and AI advice that is not backed by visible schedule state.

## Product goals
- Goals: make today’s schedule, multi-resolution timeline planning, 5-minute adjustment, and focus execution immediately understandable.
- Non-goals: fragmented route-specific calendar endpoints, silent AI application, provider/debug metadata, private model reasoning, or yearly full-payload rendering before a summary contract exists.
- Success signals: no horizontal overflow, readable dense schedules, obvious next action, polished onboarding first impression, selected-date continuity across month/week/day/agenda, and visible draft-vs-applied state.

## Personas and jobs
- Primary personas: users who want schedule operations without reading system details.
- User jobs: see today, scan month risk, adjust week/day at 5-minute precision, run one focus item, recover from drift, review pending change safely.
- Key contexts: desktop planning, mobile quick check, short focus-mode glance, month-end planning, deadline recovery.

## Information architecture
- Primary navigation: Today, Timeline, Focus Mode.
- Core routes/screens: `/dashboard`, `/schedule` timeline, `/focus`, `/onboarding`, `/login`.
- Content hierarchy: page purpose -> primary current state -> one next action -> secondary details.

## Design principles
- Principle 1: clarity before density; each card must answer one user question.
- Principle 2: hierarchy by scale and spacing, not by shouting.
- Principle 3: grid alignment is mandatory; columns and card edges must share anchors.
- Principle 4: state must be explicit; non-executable suggestions are not disabled approvals.
- Principle 5: user copy only; hide AI metadata, validation traces, private reasoning, and generated memo-like advice, but allow concise state-backed rationale for visible AI drafts.
- Principle 6: scalable timeline; month/agenda/week/day are resolutions of one workspace, not separate products.
- Principle 7: real-time drift adaptation; execution deltas must return to the timeline as visible cascade drafts.
- Tradeoffs: dense timeline surfaces may scroll horizontally on small widths rather than becoming unreadable; external virtualization/motion packages require measured evidence and explicit approval.

## Current frontend design contract
- Current direction: keep the committed calm card-based product shell, not the copied experimental redesign.
- Card usage: cards are allowed, but they must feel like one continuous workspace. Do not let the sidebar and content drift apart into separate islands.
- App shell: left navigation and main content must stay visually close. Use a modest shell gap; content should stretch beside the sidebar instead of floating in the center of unused space.
- Focus/execution mode: the main execution content is an immersive center-axis surface. Title, description, timer, and primary actions should be centered inside the main focus card.
- Timeline workspace: do not use a rigid 00:00–24:00 table as the primary desktop view. Use an Elastic Time-rail: readable cards/stacks sit on visible time anchors and 5-minute atomic snap guides. Short events remain scannable, but placement, drag/keyboard adjustment, and AI repair math snap to 5-minute increments.
- Multi-resolution schedule: `/schedule` must support view-state for `month`, `week`, `day`, and `agenda`. Week can remain the default execution view until Monthly Mosaic adoption is proven, but the IA must not describe the product as weekly-only.
- Month/agenda handoff: month day cells and agenda rows must preserve selected-date orientation when zooming into day/week. Use breadcrumb, selected-date ring, skeleton hydration, and React Query prefetch before introducing a motion library.
- Range API spine: timeline reads must use `/api/calendar/range` through the existing frontend API/query layer. Do not invent ad hoc frontend fetches for month/agenda/week summaries unless a backend PRD proves the range spine insufficient.
- Onboarding: first impression matters even if onboarding conversion is not the top product metric. The onboarding start and completion screens must look polished, calm, and trustworthy.
- Onboarding quick-start contract: frame onboarding as "choose close-enough repeated times → see today schedule", never as settings storage. The question stage must not expose a dominant percentage progress bar, provider state, private model rationale, or internal criteria.
- Onboarding responsive contract: desktop uses a purposeful left rail with outcome copy and answer readiness; mobile uses compressed choices and a sticky completion CTA once all answers are present. Mobile option helper copy stays hidden by default to keep time choices scan-friendly.
- Login/onboarding hero titles: these are not marketing billboards. They should be smaller than generic page heroes and should not dominate the card.
- Do not reintroduce oversized demo typography, heavy heading weight, excessive negative tracking, or disconnected wide gutters.

## Timeline authority and drift contract
- Authority display order:
  1. Google imported fixed commitments: shown as external fixed authority unless forked/detached.
  2. Local protected routines/events: shown as service-protected commitments.
  3. Goal-linked work: shown with deadline/risk context when relevant.
  4. Flexible local tasks/routines: eligible for AI draft movement.
  5. AI drafts: visible proposals only, not applied state.
- When authorities conflict, the UI must show the conflict in user language, not provider/debug labels. Example: `Google 일정과 보호된 루틴이 겹쳐 조정안이 필요합니다.`
- Drift detection UI must answer four questions:
  - what slipped or changed,
  - what the assistant is protecting,
  - what would move,
  - whether anything has been applied.
- Drift drafts must be reviewable from dashboard/schedule/focus entry points and must reuse the suggestion review pattern instead of inventing a hidden AI state.
- Every editable local placement and AI repair draft uses a 5-minute atomic snap grid. If a block is visually too short for text, render a compact marker/rail with accessible full time and title.

## Scalable timeline implementation contract
- First implementation slice:
  1. route-local timeline view state: `month | week | day | agenda`;
  2. `MonthlyMosaic` from `useCalendarRangeQuery({ view: "month" })`;
  3. `AgendaStream` from the same occurrence payload grouped by local date;
  4. selected-date handoff into week/day;
  5. cache invalidation via `calendarRangeQueryRootKey`;
  6. tests for month summaries, agenda order, selected-date continuity, and bounded range requests.
- Non-goals:
  - no full yearly occurrence fetch;
  - no drag-and-drop scheduling before mutation semantics are settled;
  - no `react-virtualized`, Framer Motion, or other timeline dependency until measured evidence justifies it;
  - no silent AI auto-application.

## Visual language
- Color: neutral white/gray base, one blue accent, low-saturation category tints.
- Typography: strong Korean readability; titles compact enough to preserve layout.
- Spacing/layout rhythm: 8px base rhythm, card padding 18–28px, consistent shell gutters.
- Shape/radius/elevation: rounded Apple-like surfaces, soft shadows, no heavy floating slabs.
- Motion: subtle hover only; no ornamental motion.
- Imagery/iconography: minimal; product state over decoration.

## Korean typography and line-breaking rules
- Korean is denser and more angular than English. Large, heavy Korean titles quickly look crude, crowded, and untrustworthy.
- Headings should generally use a lighter Korean weight than English display type. Current heading target is closer to `font-weight: 500` than `700/800/900`.
- Avoid huge Korean title scales. Prefer restrained `clamp()` ranges that preserve breathing room on mobile.
- Avoid aggressive negative letter-spacing. Korean title tracking should be mild; current title target is around `-0.014em` or softer for login/onboarding hero titles.
- Use enough line-height for Korean headings. Current title target is around `1.12`; login/onboarding hero titles use around `1.16`.
- Never allow Korean UI text to break by syllable in a way that reads like “로그인 재, 시도가 필, 요합니다.”
- Maintain Korean-safe wrapping:
  - `word-break: keep-all`
  - `line-break: strict`
  - balanced/pretty wrapping for headings and body copy where supported
- Button text may wrap only by sensible phrase/word boundaries. Do not force single-line buttons if that causes overflow or syllable-level breaks.
- Login and onboarding hero titles must stay smaller and calmer than full page titles.

## Components
- Existing components to reuse: `AppShell`, `SectionHeader`, `SuggestionReviewCard`, schedule/focus cards.
- New/changed components: timeline view switcher, Monthly Mosaic day cell, Agenda Stream row, selected-date zoom anchor, drift draft banner/card, and shared suggestion card states.
- Variants and states: executable, clarification required, provider unavailable, loading, empty, error, disabled, draft-not-applied, drift-detected, sync-authority-conflict.
- Token/component ownership: `app/globals.css` remains token and layout owner for now.

## Accessibility
- Target standard: keyboard usable, readable contrast, visible focus states.
- Keyboard/focus behavior: buttons and modal controls remain native controls.
- Contrast/readability: avoid low-contrast gray on tiny schedule blocks.
- Screen-reader semantics: labels and aria text must use user-facing activity text only.
- Reduced motion: current UI uses no required motion.

## Responsive behavior
- Supported breakpoints/devices: 1440 desktop, 768 tablet, 375 mobile via Playwright visual QA.
- Layout adaptations: desktop shell/sidebar, tablet stacked shell, mobile agenda/month summaries instead of dense rigid tables.
- Touch/hover differences: mobile uses larger stacked cards and full-width actions.

## Interaction states
- Loading: say what is being prepared, not system internals.
- Empty: explain the next user action.
- Error: retry-oriented, no blame.
- Success: confirm action in one sentence.
- Disabled: explain state separately; do not rely on disabled button alone.
- Offline/slow network: use “잠시 후 다시 시도” style copy.

## Content voice
- Tone: short, concrete, calm.
- Terminology: “일정”, “변경 요청”, “적용”, “검토”, “집중” consistently.
- Microcopy rules: no AI thoughts in notes; no rationale UI; no internal metadata (`confidence`, `reasoning`, `chainOfThought`, `validationTrace`, etc.); avoid awkward phrases like “적용하거나 나중에 검토할 수 있습니다” when state can be shorter.
- Korean copy must be written to wrap well. Prefer short phrases that can break naturally over long noun chains.
- Avoid machine-like or speculative service language. Do not write sentences that sound like the AI is explaining its private reasoning.
- User memo fields must contain only user-relevant schedule information. They must never contain AI-generated advice, hidden reasoning, validation evidence, provider traces, or internal repair attempts.
- Error copy should be retry-oriented and natural Korean, for example “로그인을 다시 시도해 주세요.” rather than rigid noun-heavy phrases.

## Implementation constraints
- Framework/styling system: Next.js React with centralized CSS in `app/globals.css`.
- Design-token constraints: no new dependency unless explicitly requested.
- Performance constraints: CSS/React/TanStack Query primitives first; bounded range windows, placeholder data, and prefetch before external virtualization.
- Compatibility constraints: do not fragment calendar reads into ad hoc endpoints. Use the existing `/api/calendar/range` spine and typed `lib/api.ts` query layer; any new backend shape requires PRD/test-spec approval.
- Test/screenshot expectations: run typecheck/build and Playwright visual QA after UI edits; timeline work also needs month/agenda/selected-date continuity tests.

## Release visual QA rubric
- Use this rubric for release gating, not only subjective screenshot review. A screen fails if any P0 item is observed at 375px, 768/960px, or 1440px.

### P0 visual failures
- Horizontal page overflow, clipped primary action, or controls outside the viewport.
- A primary CTA, form field, schedule block, modal action, or navigation item is hidden, overlapped, disabled without explanation, or visually detached from its section.
- Korean text breaks at awkward syllable boundaries in headings, buttons, schedule cards, or error messages.
- A single card/hero consumes so much vertical space that today/now content is pushed below the first viewport on dashboard or schedule.
- Login/onboarding/schedule/focus screens show AI internals, Google implementation details, sync diagnostics, validation traces, or provider metadata.
- Mobile and desktop use the same cramped composition instead of distinct responsive arrangements.

### P1 visual failures
- Page title, toolbar, and main content widths do not share an obvious grid anchor.
- Adjacent cards use inconsistent padding, radius, shadow, or border treatment.
- Timeline cards become visually equal-weight noise; current/next item, selected date, or drift draft is not easy to identify.
- Modal width or button layout changes unpredictably across breakpoints.
- Empty/error states feel like placeholders or mock data rather than product copy.

### Layout measurement targets
- Root page horizontal overflow: `scrollWidth <= clientWidth` at every supported viewport.
- Main content width: desktop content should align to one container rhythm; mobile content should use full-width cards with safe gutters.
- Primary action visibility: at least one obvious next action must be visible without horizontal scrolling on each core screen.
- First viewport priority:
  - dashboard: today schedule and now/next action visible;
  - schedule: selected date/current block, timeline view switcher, edit action, and AI input discoverable without feeling crowded;
  - focus: current task and primary completion/action visible;
  - onboarding/login: title, explanation, and primary action visible without oversized hero treatment.

### Screenshot review checklist
- Compare mobile and desktop screenshots separately; do not approve by checking only one viewport.
- Check edges first: left/right gutters, card alignment, clipped shadows, and fixed-width children.
- Check text second: Korean wrapping, title size, button labels, and empty/error copy.
- Check interaction affordance last: primary action, secondary action, disabled state explanation, modal close/cancel path.
- Mark screenshot evidence with route, viewport, date, and pass/fail notes.

## Current CSS anchors to preserve
- `app/globals.css` owns global layout, typography, wrapping, and product surface tokens.
- App shell spacing is intentionally modest so the left sidebar and content read as one workspace.
- Timeline desktop view must preserve `.week-stack-shell` / `.week-stack-board` compatibility while evolving toward Elastic Time-rail, not a rigid full-day table.
- `.login-hero h1` and `.onboarding-sidebar h1` intentionally override generic `h1` sizing to keep entry/onboarding screens calm.
- `.focus-primary` content is intentionally center-aligned for execution mode.
- Korean wrapping rules on `body`, headings, copy, and buttons are product requirements, not incidental CSS cleanup.

## Open questions
- [ ] None blocking; proceed with Toss structure + Apple tone and refine by screenshot evidence.
