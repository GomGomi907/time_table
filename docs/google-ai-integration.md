# Google 실제 연동 + Gemini AI 재조율 설정

## Google OAuth / Calendar / Tasks

실제 Google 연동은 목업 없이 Spring OAuth2 로그인, 저장된 refresh token, Google Calendar/Tasks REST API로 동작한다.

필수 설정:

```powershell
$env:GOOGLE_CLIENT_ID="..."
$env:GOOGLE_CLIENT_SECRET="..."
# 또는 Google Cloud Console에서 내려받은 client_secret.json 사용
$env:GOOGLE_CREDENTIALS_FILE="D:\secure\client_secret.json"
```

Google Cloud Console의 OAuth 웹 애플리케이션 Redirect URI:

```text
http://localhost:8080/login/oauth2/code/google
```

배포된 Cloud Run 서비스에서는 로컬 redirect URI만으로는 로그인할 수 없다. 같은 OAuth 웹
클라이언트의 **Authorized redirect URIs**에 공개 도메인 기반 콜백도 반드시 추가한다.

```text
https://time-table.cloud/login/oauth2/code/google
```

그리고 Cloud Run 환경 변수의 `APP_FRONTEND_URL`도 같은 origin으로 맞춘다. `time-table.cloud`
도메인 검증/Cloud Run 매핑이 끝나기 전 smoke fallback은
`https://timetable-608682434352.asia-northeast1.run.app`를 사용한다.

```text
APP_FRONTEND_URL=https://time-table.cloud
NEXT_PUBLIC_SITE_URL=https://time-table.cloud
NEXT_PUBLIC_API_BASE_URL=
```

현재 로컬 `client_secret_*.json`에 `http://localhost:8080/login/oauth2/code/google`만
들어있다면 Google Console 등록값도 로컬 전용일 가능성이 높다. 이 경우 배포본 OAuth는
`redirect_uri_mismatch` 또는 계정 연결 실패로 끝난다.

`invalid_token_response`와 `401 Unauthorized`가 같이 보이면 redirect 단계는 통과했지만 token
교환에서 OAuth 클라이언트 인증이 실패한 것이다. 이때는 Cloud Run의 `GOOGLE_CLIENT_ID`와
`GOOGLE_CLIENT_SECRET`이 같은 OAuth 웹 클라이언트의 값인지 확인한다. Secret Manager를 쓸 때
secret 이름이나 Gemini API 키를 `GOOGLE_CLIENT_SECRET` 값으로 넣으면 안 된다. 값 앞뒤 공백,
따옴표, `GOOGLE_CLIENT_SECRET=...` 형태는 백엔드가 정규화한다. 실수로 Google Console에서 내려받은
`client_secret.json` 전체를 Secret 값으로 넣은 경우에도 백엔드가 `web.client_secret`을 추출한다.

백엔드는 Google token endpoint에 `client_secret_post` 방식으로 `client_id`와 `client_secret`을
전송한다. 배포 후에도 `invalid_token_response 401`이 계속되면 코드의 redirect URI 단계가 아니라
Cloud Run/Secret Manager에 배포된 OAuth client secret 값이 Google Console의 현재 값과 다른 것이다.

요청 scope:

```text
openid profile email
https://www.googleapis.com/auth/calendar.events
https://www.googleapis.com/auth/tasks
```

구현 메모:

- 로그인 요청에는 refresh token을 받기 위해 `access_type=offline`, `prompt=consent`, `include_granted_scopes=true`를 붙인다.
- access token이 없거나 만료 임박이면 `GOOGLE_TOKEN_URL` 기본값인 `https://oauth2.googleapis.com/token`에 `grant_type=refresh_token`으로 갱신한다.
- Calendar 이벤트 생성/수정/삭제는 `/calendar/v3/calendars/primary/events` 계열 엔드포인트를 사용한다.
- Tasks 생성/수정/삭제는 `/tasks/v1/lists/{tasklist}/tasks` 계열 엔드포인트를 사용한다.
- 로컬 목업을 쓰려면 `app.sync.google.mock-enabled=true`; 실제 REST 경로는 해당 값을 끄거나 생략한다.

## Gemini API AI 일정 재조율

AI 재조율과 주간 시간표 정규화는 Google Gemini API의 `generateContent` 엔드포인트를 호출한다. 기본 base URL은 `https://generativelanguage.googleapis.com/v1beta`, 기본 모델은 `gemini-2.5-flash`다.

```powershell
$env:APP_AI_ENABLED="true"
$env:APP_GEMINI_API_KEY="..."
$env:APP_AI_MODEL="gemini-2.5-flash"
# 기본값을 바꿔야 할 때만 설정
$env:APP_AI_BASE_URL="https://generativelanguage.googleapis.com/v1beta"
```

동작:

1. `/api/agent/reschedule` 요청이 들어오면 현재 사용자, 주간 시간표 블록, 일정, 미완료 태스크를 AI 컨텍스트로 묶고 Context Package 메타데이터(요청 유형, 포함/제외 섹션, 추정 크기, privacy exposure score)를 만든다.
2. 백엔드는 Gemini `models/{model}:generateContent` 요청에 `x-goog-api-key` 헤더와 JSON 응답 설정을 붙인다.
3. AI는 JSON Schema로 제한된 `StructuredAiCommandBatch`를 반환한다.
4. 백엔드는 raw command batch 위에 `AiDecisionPackage`를 만들어 UI와 품질 측정이 request kind, trust level, affected items, confirmation need, privacy block을 읽게 한다.
5. 반환된 `move_event`, `update_event`, `create_event`, `delete_event`, `recommend_task` 명령은 사용자가 제안을 적용할 때 로컬 canonical Event/Task/ScheduleBlock에 반영된다.
6. Google에서 온 일정/태스크를 변경하면 provider write outbox가 예약되어 다음 outbound sync 때 Google Calendar/Tasks로 전파된다. 외부 일정의 직접 삭제는 현재 trust milestone에서 허용하지 않는다.

UI/보고서에는 raw prompt, reasoning trace, validation internals, provider metadata, API key가 노출되면 안 된다. Live smoke는 `.omx/reports/llm-live-probe-*.json`에 latency, scenario id/request kind, validation/safety verdict, command count, privacy exposure score, estimated character size를 남긴다.

AI가 꺼져 있으면 요청은 “검토 대기” suggestion으로만 저장되고, 실행 가능한 AI 변경 명령은 만들지 않는다.
