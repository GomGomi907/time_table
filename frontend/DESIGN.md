# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-05-30
- Primary product surfaces: login, onboarding, dashboard, weekly schedule, focus mode.
- Evidence reviewed: `components/*-view.tsx`, `components/app-shell.tsx`, `app/globals.css`, Playwright screenshots under `test-results/visual-qa-captures-core-local-visual-QA-surfaces-chromium/`, frontend/backend handoff `.omx/handoffs/frontend-backend-ai-agent-next-iteration-20260530.md`.

## Brand
- Personality: Toss-like product structure, Apple-like calm tone; precise, quiet, trustworthy.
- Trust signals: clear hierarchy, aligned grids, restrained shadows, concise copy, no AI internals, no fake reasoning.
- Avoid: oversized demo typography, cramped cards, AI rationale, provider/debug metadata, “AI thoughts” in user memo fields.

## Product goals
- Goals: make today’s schedule, weekly adjustment, and focus execution immediately understandable.
- Non-goals: changing API contracts, adding backend fields, exposing AI rationale.
- Success signals: no horizontal overflow, readable dense schedules, obvious next action, polished onboarding first impression.

## Personas and jobs
- Primary personas: users who want schedule operations without reading system details.
- User jobs: see today, adjust week, run one focus item, review pending change safely.
- Key contexts: desktop planning, mobile quick check, short focus-mode glance.

## Information architecture
- Primary navigation: Today, Weekly Schedule, Focus Mode.
- Core routes/screens: `/dashboard`, `/schedule`, `/focus`, `/onboarding`, `/login`.
- Content hierarchy: page purpose -> primary current state -> one next action -> secondary details.

## Design principles
- Principle 1: clarity before density; each card must answer one user question.
- Principle 2: hierarchy by scale and spacing, not by shouting.
- Principle 3: grid alignment is mandatory; columns and card edges must share anchors.
- Principle 4: state must be explicit; non-executable suggestions are not disabled approvals.
- Principle 5: user copy only; hide AI metadata, reasoning, validation traces, and generated memo-like advice.
- Tradeoffs: dense weekly schedules may scroll horizontally on small widths rather than becoming unreadable.

## Current frontend design contract
- Current direction: keep the committed calm card-based product shell, not the copied experimental redesign.
- Card usage: cards are allowed, but they must feel like one continuous workspace. Do not let the sidebar and content drift apart into separate islands.
- App shell: left navigation and main content must stay visually close. Use a modest shell gap; content should stretch beside the sidebar instead of floating in the center of unused space.
- Focus/execution mode: the main execution content is an immersive center-axis surface. Title, description, timer, and primary actions should be centered inside the main focus card.
- Weekly schedule: do not use a fixed time-axis table as the primary desktop view. Short events become too hard to scan. Use a day-by-day stack where each day shows morning-to-night schedule cards in order, with start/end time as card metadata.
- Onboarding: first impression matters even if onboarding conversion is not the top product metric. The onboarding start and completion screens must look polished, calm, and trustworthy.
- Login/onboarding hero titles: these are not marketing billboards. They should be smaller than generic page heroes and should not dominate the card.
- Do not reintroduce oversized demo typography, heavy heading weight, excessive negative tracking, or disconnected wide gutters.

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
- New/changed components: no new API-facing component required; shared suggestion card owns user-facing suggestion states.
- Variants and states: executable, clarification required, provider unavailable, loading, empty, error, disabled.
- Token/component ownership: `app/globals.css` remains token and layout owner for now.

## Accessibility
- Target standard: keyboard usable, readable contrast, visible focus states.
- Keyboard/focus behavior: buttons and modal controls remain native controls.
- Contrast/readability: avoid low-contrast gray on tiny schedule blocks.
- Screen-reader semantics: labels and aria text must use user-facing activity text only.
- Reduced motion: current UI uses no required motion.

## Responsive behavior
- Supported breakpoints/devices: 1440 desktop, 768 tablet, 375 mobile via Playwright visual QA.
- Layout adaptations: desktop shell/sidebar, tablet stacked shell, mobile card agenda instead of dense grid.
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
- Terminology: “일정”, “변경 요청”, “적용”, “보류”, “집중” consistently.
- Microcopy rules: no AI thoughts in notes; no rationale UI; no internal metadata (`confidence`, `reasoning`, `chainOfThought`, `validationTrace`, etc.); avoid awkward phrases like “적용하거나 보류할 수 있습니다” when state can be shorter.
- Korean copy must be written to wrap well. Prefer short phrases that can break naturally over long noun chains.
- Avoid machine-like or speculative service language. Do not write sentences that sound like the AI is explaining its private reasoning.
- User memo fields must contain only user-relevant schedule information. They must never contain AI-generated advice, hidden reasoning, validation evidence, provider traces, or internal repair attempts.
- Error copy should be retry-oriented and natural Korean, for example “로그인을 다시 시도해 주세요.” rather than rigid noun-heavy phrases.

## Implementation constraints
- Framework/styling system: Next.js React with centralized CSS in `app/globals.css`.
- Design-token constraints: no new dependency unless explicitly requested.
- Performance constraints: CSS-only visual refinements preferred where possible.
- Compatibility constraints: API contract frozen; do not change `lib/api.ts` request/response shape or backend.
- Test/screenshot expectations: run typecheck/build and Playwright visual QA after UI edits.

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
- Weekly schedule cards become visually equal-weight noise; current/next item is not easy to identify.
- Modal width or button layout changes unpredictably across breakpoints.
- Empty/error states feel like placeholders or mock data rather than product copy.

### Layout measurement targets
- Root page horizontal overflow: `scrollWidth <= clientWidth` at every supported viewport.
- Main content width: desktop content should align to one container rhythm; mobile content should use full-width cards with safe gutters.
- Primary action visibility: at least one obvious next action must be visible without horizontal scrolling on each core screen.
- First viewport priority:
  - dashboard: today schedule and now/next action visible;
  - schedule: today/current block, weekly stack, edit action, and AI input discoverable without feeling crowded;
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
- Weekly schedule desktop view intentionally uses `.week-stack-shell` / `.week-stack-board` instead of a rigid time grid.
- `.login-hero h1` and `.onboarding-sidebar h1` intentionally override generic `h1` sizing to keep entry/onboarding screens calm.
- `.focus-primary` content is intentionally center-aligned for execution mode.
- Korean wrapping rules on `body`, headings, copy, and buttons are product requirements, not incidental CSS cleanup.

## Open questions
- [ ] None blocking; proceed with Toss structure + Apple tone and refine by screenshot evidence.
