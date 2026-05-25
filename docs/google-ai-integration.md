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

1. `/api/agent/reschedule` 요청이 들어오면 현재 사용자, 주간 시간표 블록, 일정, 미완료 태스크를 AI 컨텍스트로 묶는다.
2. 백엔드는 Gemini `models/{model}:generateContent` 요청에 `x-goog-api-key` 헤더와 JSON 응답 설정을 붙인다.
3. AI는 JSON Schema로 제한된 `StructuredAiCommandBatch`를 반환한다.
4. 반환된 `move_event`, `update_event`, `create_event`, `delete_event`, `recommend_task` 명령은 사용자가 제안을 적용할 때 로컬 canonical Event/Task/ScheduleBlock에 반영된다.
5. Google에서 온 일정/태스크를 변경하면 provider write outbox가 예약되어 다음 outbound sync 때 Google Calendar/Tasks로 전파된다.

AI가 꺼져 있으면 요청은 “검토 대기” suggestion으로만 저장되고, 실행 가능한 AI 변경 명령은 만들지 않는다.
