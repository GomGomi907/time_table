# Final submission validation report

- Output directory: `docs/final-submission/`
- Deployment URL: https://timetable-608682434352.asia-northeast2.run.app/
- GitHub: https://github.com/GomGomi907/time_table.git
- Logo PNG used: `frontend/public/brand/time-table-logo-512.png`
- Logo SVG referenced: `frontend/public/brand/time-table-logo.svg`

## Core artifacts

- PPTX: `docs/final-submission/Time_Table_Final_Presentation_32183022.pptx` (2039042 bytes, 12 slides)
- Report DOCX: `docs/final-submission/Time_Table_Final_Report_32183022.docx` (1435978 bytes, 48 non-empty paragraphs)
- Individual reflection MD: `docs/final-submission/Time_Table_Individual_Reflection_32183022.md` (7934 bytes)
- Individual reflection PDF: `docs/final-submission/Time_Table_Individual_Reflection_32183022.pdf` (127029 bytes, 2 pages)

## Screenshot evidence

- Fresh screenshots: 6 PNG files, all 1600x1000
- `docs/final-submission/screenshots/01-login.png` (387373 bytes)
- `docs/final-submission/screenshots/02-onboarding.png` (396855 bytes)
- `docs/final-submission/screenshots/03-dashboard.png` (259050 bytes)
- `docs/final-submission/screenshots/04-schedule.png` (265630 bytes)
- `docs/final-submission/screenshots/05-schedule-add-demo.png` (197557 bytes)
- `docs/final-submission/screenshots/06-focus.png` (493430 bytes)

## Runtime evidence

- `npm run e2e:local -- e2e/.presentation-capture.spec.ts` completed successfully earlier in this Ralph run.
- It performed a production Next.js build, started Spring Boot backend with mock login/mock Google sync/H2 DB, inserted presentation mock data, and captured screenshots.
- Playwright result: 1 passed.

## Validation notes

- PPTX structure: PASS, 12 slides within the 10-12 slide target.
- DOCX structure: PASS by package/text inspection.
- Reflection PDF: PASS by pypdf page count and pypdfium2 visual render preview in `individual-reflection-preview/`.
- LibreOffice/Poppler were unavailable, so Office files were not rendered to PDF/images through LibreOffice.
