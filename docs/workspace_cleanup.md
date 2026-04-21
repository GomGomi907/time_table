# Workspace Cleanup Policy

## Primary Direction
- `backend`는 Spring Boot를 주력 백엔드로 유지한다.
- Rust MVP 관련 파일은 삭제하지 않고 루트 `archive/legacy-backend/`로 이동해 보관한다.
- `frontend`는 현재 작업 브랜치를 유지하고, 재생성 가능한 캐시와 빌드 산출물만 정리한다.

## Archive Rules
- `archive/legacy-backend/`
  - Rust MVP 소스, SQLite 기반 실험 파일, 중복 백엔드 스캐폴드 보관
- `archive/presentations/`
  - 루트 최신본을 제외한 PPTX/PDF/디자인 산출물 보관
- `archive/generated-assets/`
  - 렌더 출력물, 디자인 export, 실험 결과물, 외부 도구 생성물 보관

## Root Retention Rules
- 루트 유지 대상 발표 산출물은 최신본 기준으로 관리한다.
- 현재 루트 유지 기준:
  - `타임테이블_글자축소.pptx`
  - `타임테이블_기획문서.pdf`
- 그 외 이전 버전 발표자료와 렌더 결과는 archive로 이동한다.

## Regenerable Files
- 삭제 또는 재설치 대상으로 본다.
  - `frontend/node_modules`
  - `frontend/.next`
  - `backend/target`
  - `backend/.gradle`
  - `backend/build`
  - `gemma4/.venv`
  - `gemma4/__pycache__`
  - `docs/ppt_gen/venv`
  - `docs/ppt_gen/__pycache__`

## Notes
- 로컬 실행에 필요한 비밀값은 저장소 기준 파일이 아니라 로컬 환경 변수로 관리한다.
- archive로 이동한 항목은 참조용 보관본이며, 주력 구현 경로와 분리해 혼선을 줄인다.
