# Time_table API 문서

업데이트: 2026-04-19

이 문서는 현재 프론트 화면이 실제로 사용하는 백엔드 계약만 정리합니다.  
주력 백엔드는 Spring Boot이고, 일정 import 정규화는 로컬 `gemma4`를 사용합니다.

## 1. 공통 기준

- Base URL: `http://localhost:8080/api`
- 인증 방식: Google OAuth 세션 + 로컬 기본 사용자
- 프론트 기본 페이지:
  - `/login`
  - `/dashboard`
  - `/schedule`
  - `/goals`
  - `/settings`
  - `/auth/callback`
  - `/api-docs`

## 2. 인증 및 세션

### `GET /api/auth/session`

현재 사용자 세션 상태를 반환합니다.

응답 shape:

- `authenticated: boolean`
- `userId: string`
- `email: string | null`
- `displayName: string | null`
- `googleConnectionStatus: CONNECTED | DEGRADED | NOT_CONNECTED`
- `lastSyncAt: string | null`
- `callbackUrl: string`

사용 화면:

- `/login`
- `/dashboard`
- `/settings`
- `/auth/callback`

### `GET /api/auth/google/start`

Google OAuth 시작 URL을 반환합니다.

응답 shape:

- `enabled: boolean`
- `url: string | null`
- `message: string | null`

사용 화면:

- `/login`
- `/settings`

### `POST /api/auth/logout`

현재 세션을 종료합니다.

사용 화면:

- 앱 셸 로그아웃

## 3. 온보딩 상태

### `GET /api/onboarding/status`

현재 설정 상태와 다음 권장 단계를 반환합니다.

응답 shape:

- `googleConnected: boolean`
- `routinesReady: boolean`
- `goalsReady: boolean`
- `preferencesReady: boolean`
- `scheduleReady: boolean`
- `nextStep: string`

## 4. 목표

### `GET /api/goals`

목표 목록을 평면 구조로 반환합니다.

응답 shape:

- `id: string`
- `parentId: string | null`
- `title: string`
- `description: string | null`
- `category: HEALTH | CAREER | FINANCE | GROWTH | HOBBY | OTHER`
- `status: PENDING | IN_PROGRESS | COMPLETED | FAILED`
- `progress: number`

사용 화면:

- `/dashboard`
- `/goals`

### `POST /api/goals`

새 목표를 추가합니다.

요청 body:

- `parentId?: string | null`
- `title: string`
- `description?: string | null`
- `category?: HEALTH | CAREER | FINANCE | GROWTH | HOBBY | OTHER`
- `status?: PENDING | IN_PROGRESS | COMPLETED | FAILED`
- `progress?: number`

## 5. 시간표

### `GET /api/schedule/week`

요일별 블록 배열로 현재 주간 시간표를 반환합니다.

응답 shape:

- `week: Array<{ dayOfWeek, blocks }>`
- `blocks[].id: string`
- `blocks[].startTime: string`
- `blocks[].endTime: string`
- `blocks[].activity: string`
- `blocks[].category: WORK | LIFE | TRANSIT | GROWTH | HOBBY | SLEEP | ADMIN`
- `blocks[].note?: string | null`
- `blocks[].sourceType: DEFAULT_ROUTINE | GEMMA_IMPORT | MANUAL`
- `blocks[].sourceRef?: string | null`

사용 화면:

- `/dashboard`
- `/schedule`

### `POST /api/schedule/import`

텍스트로 붙여넣은 주간 계획을 로컬 `gemma4`로 정규화해 반영합니다.

요청 body:

- `rawText: string`
- `replaceExisting: boolean`

사용 화면:

- `/schedule`

### `POST /api/schedule/blocks`

수동 블록을 추가합니다.

요청 body:

- `dayOfWeek: Monday | Tuesday | Wednesday | Thursday | Friday | Saturday | Sunday`
- `startTime: HH:mm`
- `endTime: HH:mm`
- `activity: string`
- `category: WORK | LIFE | TRANSIT | GROWTH | HOBBY | SLEEP | ADMIN`
- `note?: string | null`

### `PUT /api/schedule/blocks/{blockId}`

기존 블록을 수정합니다.

요청 body:

- `POST /api/schedule/blocks` 와 동일

### `DELETE /api/schedule/blocks/{blockId}`

기존 블록을 삭제합니다.

## 6. 설정

### `GET /api/settings`

현재 사용자 설정을 반환합니다.

응답 shape:

- `id: string`
- `quietHoursStart: string`
- `quietHoursEnd: string`
- `bufferMinutes: number`
- `overtimeTriggerMinutes: number`
- `openGapTriggerMinutes: number`
- `interventionFrequency: string`

사용 화면:

- `/settings`

### `PUT /api/settings`

설정 값을 부분 또는 전체 갱신합니다.

요청 body:

- `quietHoursStart?: HH:mm`
- `quietHoursEnd?: HH:mm`
- `bufferMinutes?: number`
- `overtimeTriggerMinutes?: number`
- `openGapTriggerMinutes?: number`
- `interventionFrequency?: string`

## 7. 공통 오류 응답

백엔드 예외는 아래 JSON 형태로 정규화됩니다.

- `timestamp: string`
- `status: number`
- `error: string`
- `message: string`
- `path: string`
