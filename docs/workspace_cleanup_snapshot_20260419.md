# Workspace Cleanup Snapshot (2026-04-19)

## Summary
- 정리 전 기준 스냅샷이다.
- 루트는 발표자료, 문서, 프론트/백엔드 코드, 실험용 스크립트와 생성 산출물이 혼재된 상태였다.
- `frontend`와 `backend`는 각각 별도 Git 저장소였다.

## Large Regenerable Targets
- `gemma4/.venv`: 약 4487.60 MB
- `backend/target`: 약 1840.01 MB
- `frontend/node_modules`: 약 438.72 MB
- `frontend/.next`: 약 227.12 MB
- `docs/ppt_gen/venv`: `docs/ppt_gen` 내 대용량 가상환경

## Legacy / Duplicate Targets
- `backend/Cargo.toml`
- `backend/Cargo.lock`
- `backend/src/main.rs`
- `backend/migrations/`
- `backend/mvp.db`
- `backend/backend/`
- `backend/.env` (`DATABASE_URL`, `RUST_LOG`, Rust OAuth redirect 설정 포함)

## Generated / Exported Targets
- `docs/ppt_gen/rendered_midterm/`
- `docs/ppt_gen/rendered_timetable/`
- `docs/ppt_gen/__pycache__/`
- `stitch/`
- `stitch.zip`

## Root Presentation Files Before Cleanup
- 유지 후보
  - `타임테이블_글자축소.pptx`
  - `타임테이블_기획문서.pdf`
- archive 이동 후보
  - `타임테이블.pptx`
  - `타임테이블_발표자료.pptx`
  - `타임테이블_기획서체_단순디자인.pptx`
  - `AI_Schedule_Operator_Executive_Redesign_retry_20260415.pptx`
  - `AI_Schedule_Operator_Executive_Redesign_retry_20260415 (1).pptx`
