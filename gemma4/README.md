# Gemma 4 Workspace

이 디렉토리는 Time Table의 로컬 LLM 실험 자산과 Gemma 4 관련 보조 스크립트를 담고 있습니다.  
현재 앱의 기본 추론 경로는 Python 스크립트 직접 실행이 아니라, `vLLM`의 OpenAI 호환 서버를 백엔드가 호출하는 구조입니다.

## 현재 기준 실행 방식

권장 방식은 루트에서 컨테이너로 `vLLM + Gemma 4`를 띄우는 것입니다.

```bash
docker compose --profile llm up --build
```

기본 모델은 `google/gemma-4-E2B-it`이며, 루트 `.env` 또는 `.env.example` 기준으로 아래 값을 맞추면 됩니다.

```env
HF_TOKEN=hf_your_token_here
APP_AI_API_KEY=time-table-local
APP_AI_MODEL=google/gemma-4-E2B-it
VLLM_MAX_MODEL_LEN=8192
VLLM_GPU_MEMORY_UTILIZATION=0.9
```

`HF_TOKEN`은 Hugging Face의 Gemma 4 gated repo 접근 권한이 있어야 실제 다운로드가 됩니다.

## 남아 있는 로컬 실험 자산

아래 파일들은 프롬프트 실험, 스키마 검증, 오프라인 하네스 테스트 용도로 유지합니다.

- `gemma_cli.py`: 로컬 단발/대화형 테스트용 CLI
- `weekly_schedule_normalizer.py`: 과거 로컬 정규화 스크립트
- `timetable_harness.py`: 타임테이블 정규화 하네스
- `prompts/timetable/`: 단계별 프롬프트
- `schemas/timetable_normalized_schema.json`: 정규화 스키마
- `fixtures/timetable_cases.json`: 샘플 케이스

## 로컬 하네스 실행

컨테이너 추론과 별개로 프롬프트 실험만 확인하고 싶다면 기존 Python 하네스를 그대로 쓸 수 있습니다.

```powershell
Copy-Item .env.example .env
.\scripts\setup.ps1 -ComputePlatform cu128
.\.venv\Scripts\python.exe .\timetable_harness.py --case basic_multiday_ko
```

리포트는 `runs/timetable-YYYYMMDD-HHMMSS/report.json`에 저장됩니다.
