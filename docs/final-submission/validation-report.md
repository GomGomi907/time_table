# Final submission validation report

- Generated at: 2026-06-11 Asia/Seoul Ralph run
- Output directory: `docs/final-submission/`
- Logo PNG used: `frontend/public/brand/time-table-logo-512.png`
- Logo SVG referenced: `frontend/public/brand/time-table-logo.svg`

## Screenshot evidence

- Fresh screenshots: 6 PNG files
- Dimensions: all captured at 1600x1000
- Files:
  - `docs/final-submission/screenshots/01-login.png` (387373 bytes)
  - `docs/final-submission/screenshots/02-onboarding.png` (396855 bytes)
  - `docs/final-submission/screenshots/03-dashboard.png` (259050 bytes)
  - `docs/final-submission/screenshots/04-schedule.png` (265630 bytes)
  - `docs/final-submission/screenshots/05-schedule-add-demo.png` (197557 bytes)
  - `docs/final-submission/screenshots/06-focus.png` (493430 bytes)

## PPTX evidence

- File: `docs/final-submission/Time_Table_Final_Presentation_32183022.pptx`
- Size: 2038973 bytes
- Slide count: 12
- Slide XML count: 12
- Embedded media count: 7
- Required range: 10–12 slides -> PASS
- Contains course/professor/student metadata, ADHD/executive-function motivation, demo guidance, GitHub/source submission notes -> PASS by text extraction

## DOCX evidence

- File: `docs/final-submission/Time_Table_Final_Report_32183022.docx`
- Size: 1435922 bytes
- Non-empty paragraphs: 48
- Embedded media count: 5
- Structure: introduction/problem, goals, service flow, implementation, screenshots/status, demo, limitations/conclusion -> PASS by text extraction

## Runtime evidence

- `npm run e2e:local -- e2e/.presentation-capture.spec.ts` completed successfully.
- This command performed a production Next.js build, started Spring Boot backend with mock login/mock Google sync/H2 DB, started frontend, inserted presentation mock data, and captured screenshots.
- Playwright result: 1 passed.

## Known limitations

- LibreOffice/Poppler render validation was unavailable in this environment, so PPTX/DOCX were validated by package structure and text extraction rather than PDF/image rendering.
- Existing working tree had unrelated source modifications before this task; they were not reverted or overwritten.
- A real deployment URL was not discovered in the repo; demo script treats deployment link as optional and recommends a live click-through plus screenshot fallback.
