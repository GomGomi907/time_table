# Deprecated Gemma 4 Workspace

이 디렉토리는 과거 로컬 LLM/Gemma 4 프롬프트 실험 자산을 보관하는 공간입니다. 현재 서비스 앱의 AI 기능은 더 이상 `vLLM` 또는 OpenAI 호환 로컬 서버를 기본 경로로 사용하지 않습니다.

현재 백엔드 AI 경로는 루트 설정의 Google Gemini API입니다.

```env
APP_AI_ENABLED=true
APP_GEMINI_API_KEY=
APP_AI_MODEL=gemini-2.5-flash
APP_AI_BASE_URL=https://generativelanguage.googleapis.com/v1beta
```

## 남아 있는 로컬 실험 자산

아래 파일들은 프롬프트 실험, 스키마 검증, 오프라인 하네스 테스트 용도로만 유지합니다. 서비스 런타임이나 Docker Compose 배포에는 연결되어 있지 않습니다.

- `gemma_cli.py`: 과거 로컬 단발/대화형 테스트용 CLI
- `weekly_schedule_normalizer.py`: 과거 로컬 정규화 스크립트
- `timetable_harness.py`: 타임테이블 정규화 하네스
- `prompts/timetable/`: 단계별 프롬프트
- `schemas/timetable_normalized_schema.json`: 정규화 스키마
- `fixtures/timetable_cases.json`: 샘플 케이스

## 로컬 하네스 실행

서비스와 분리된 프롬프트 실험만 확인하고 싶다면 기존 Python 하네스를 수동으로 사용할 수 있습니다. 이 경로는 운영 배포 검증 대상이 아닙니다.

```powershell
Copy-Item .env.example .env
.\scripts\setup.ps1 -ComputePlatform cu128
.\.venv\Scripts\python.exe .\timetable_harness.py --case basic_multiday_ko
```

리포트는 `runs/timetable-YYYYMMDD-HHMMSS/report.json`에 저장됩니다.
