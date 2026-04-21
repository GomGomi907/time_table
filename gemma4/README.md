# Gemma CLI Boilerplate

Google AI Gemma Core 문서의 `Hugging Face Transformers로 Gemma 실행` 예제를 기준으로, 로컬에서 바로 테스트할 수 있게 만든 최소 CLI 세팅입니다.

기준 문서:

- Gemma Core 개요: https://ai.google.dev/gemma/docs/core?hl=ko
- Transformers 실행 가이드: https://ai.google.dev/gemma/docs/core/huggingface_inference?hl=ko

## 포함된 파일

- `gemma_cli.py`: 단발 프롬프트 + 인터랙티브 채팅 CLI
- `scripts/setup.ps1`: Windows PowerShell 기준 설치 스크립트
- `.env.example`: 모델 ID와 토큰 예시
- `requirements.txt`: 최소 의존성

## 빠른 시작

PowerShell에서 아래 순서로 실행합니다.

```powershell
cd D:\gemma4
Copy-Item .env.example .env
.\scripts\setup.ps1 -ComputePlatform cu128
.\.venv\Scripts\python.exe .\gemma_cli.py --help
```

`.env`에서 최소한 아래 항목을 확인하세요.

```env
HF_TOKEN=hf_your_token_here
GEMMA_MODEL_ID=google/gemma-4-E2B-it
GEMMA_TASK=any-to-any
```

`HF_TOKEN`은 Hugging Face에서 Gemma 모델 접근 권한이 필요한 경우 사용합니다.
Gemma 모델은 gated repo이므로, Hugging Face에서 해당 모델 접근 승인이 되어 있어야 실제 다운로드가 됩니다.

현재 Windows Python 배포판에 따라 `venv` 내부 `pip` 부트스트랩이 실패할 수 있습니다. 그런 경우에는 먼저 아래 fallback으로 바로 실행 확인이 가능합니다.

```powershell
py -m pip install --index-url https://download.pytorch.org/whl/cu128 torch
py -m pip install -r requirements.txt
py .\gemma_cli.py --help
```

CPU로만 설치하려면:

```powershell
.\scripts\setup.ps1 -ComputePlatform cpu
```

## 실행 예시

단발 요청:

```powershell
.\.venv\Scripts\python.exe .\gemma_cli.py "한국어로 Gemma를 한 문단으로 소개해줘."
```

시스템 프롬프트 포함:

```powershell
.\.venv\Scripts\python.exe .\gemma_cli.py `
  --system "You are a concise bilingual assistant." `
  "Gemma 4의 장점을 3줄로 정리해줘."
```

인터랙티브 채팅:

```powershell
.\.venv\Scripts\python.exe .\gemma_cli.py --interactive
```

## 타임테이블 정규화 하네스

타임테이블 앱용으로 `분석 -> 정규화 -> 검증` 3단계 프롬프트 하네스를 추가했습니다.

- 실행기: `timetable_harness.py`
- 프롬프트: `prompts/timetable/`
- 스키마: `schemas/timetable_normalized_schema.json`
- 샘플 케이스: `fixtures/timetable_cases.json`

전체 테스트:

```powershell
.\.venv\Scripts\python.exe .\timetable_harness.py --case all
```

단일 케이스:

```powershell
.\.venv\Scripts\python.exe .\timetable_harness.py --case basic_multiday_ko
```

리포트는 `runs/timetable-YYYYMMDD-HHMMSS/report.json`에 저장됩니다.

기본값은 앱 적재 안정성을 위해 `native thinking`을 끄고, 대신 다단계 프롬프트 체인으로 reasoning을 분리합니다. Gemma의 native thinking 채널은 디버그용 옵션 `--native-thinking`으로 남겨두었지만, strict JSON 출력에서는 불안정할 수 있습니다.

## 모델/태스크 변경

공식 문서 예제는 `google/gemma-4-E2B-it`와 `any-to-any` 태스크를 사용합니다.

더 가벼운 텍스트 전용 테스트를 원하면 `.env`를 다음처럼 바꿔서 시작할 수 있습니다.

```env
GEMMA_MODEL_ID=google/gemma-3-1b-it
GEMMA_TASK=text-generation
```

명령행에서 직접 덮어쓸 수도 있습니다.

```powershell
.\.venv\Scripts\python.exe .\gemma_cli.py `
  --model google/gemma-3-1b-it `
  --task text-generation `
  "짧은 인사말을 만들어줘."
```

## 옵션

주요 옵션:

- `--model`: Hugging Face 모델 ID
- `--task`: Transformers pipeline task
- `--system`: 시스템 프롬프트
- `--max-new-tokens`: 최대 생성 토큰 수
- `--temperature`: 샘플링 온도. `0`이면 greedy
- `--top-p`: top-p 샘플링 값
- `--interactive`: 멀티턴 채팅 모드
- `--json`: raw pipeline 출력 확인

## 참고

- `device_map="auto"`와 `dtype="auto"`는 공식 예제 방향을 그대로 따릅니다.
- 큰 모델은 VRAM/RAM 요구량이 높습니다. 먼저 작은 모델로 연결 확인 후 올리는 편이 안전합니다.
