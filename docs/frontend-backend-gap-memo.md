# Frontend Backend Gap Memo

Date: 2026-04-23

## 이번 구현에서 연결한 범위

- 새 Next.js App Router 프론트를 `frontend/`에 다시 구성했다.
- `dashboard`, `schedule`, `focus`, `login`, `auth/callback` 화면을 백엔드 API에 연결했다.
- 세션 확인과 CSRF 쿠키 처리 후 보호된 POST 요청을 보내도록 프론트 API 레이어를 만들었다.
- 일정 블록 생성, 포커스 완료/연장/미루기, reschedule suggestion 생성/적용/거절까지 최소 액션을 연결했다.

## 현재 확인된 백엔드 의존 / 부족한 점

- `GET /api/focus/current`는 이벤트/태스크 중심 포커스를 유지하되, 일정 블록 기반 현재/다음 컨텍스트와 DB-backed focus preference context를 함께 내려준다.
- 프론트의 시간표 기반 fallback은 보조 안전망으로 남아 있지만, 기본 포커스 안내는 백엔드 DTO를 우선 사용한다.
- `GET /api/sync/status`와 관련 수동 sync API는 Google Calendar/Tasks inbound read와 승인 후 provider write outbox 단계까지 연결됐다.
- 반복 sync는 provider `etag`가 동일한 imported row를 다시 저장하지 않으므로, 변경 없음 상태의 affected count를 0으로 유지한다.
- 프론트는 읽기 성공/대기/재연동 필요 상태만 표시해야 한다. 외부 provider write-back은 outbox flush 완료 전까지 끝난 상태로 가정하면 안 된다.
- `POST /api/agent/reschedule`와 suggestion apply flow는 중간 명령 실패 시 전체 롤백하도록 보강됐다.
- 프론트는 이 흐름을 그대로 노출했지만, 복잡한 재배치 계산 결과를 기대하는 UX로 확장하려면 백엔드 설명/상세 payload가 더 필요하다.
- 대시보드 1차 집계용 전용 API가 추가됐다.
- 현재 `GET /api/dashboard/summary`가 주간 시간표, 목표, 현재 포커스, sync 상태, 제안을 한 번에 내려준다.
- 완료율, 주간 구성도, 최상위 목표 같은 대시보드 표시용 파생값은 백엔드 `metrics` DTO에서 내려준다.
- 온보딩 완료 여부와 주요 생활 리듬 답변은 `onboarding_profiles`에 저장된다.
- 2026-06-02 기준 프론트 소스는 온보딩 완료/답변 handoff에 `localStorage`를 사용하지 않는다. 온보딩 완료 여부와 답변의 canonical source는 `onboarding_profiles`이며, `frontend/scripts/verify-hygiene.mjs`와 Playwright storage policy test가 재도입을 막는다.
- 집중 단위, 회복 버퍼, AI 개입 강도는 `onboarding_profiles`와 `user_preferences`에 DB-backed로 저장된다.
- 현재 프론트는 `취침/기상`과 focus preference를 `settings`에도 반영하고, 루틴 답변은 DB 기반 온보딩 프로필을 기준으로 사용한다.
- 주간 시간표는 백엔드가 내려준 블록을 프론트에서 임의 dedupe하지 않는다. 중복/겹침 방지는 `ScheduleBlockRules`와 schedule API 테스트가 소유한다.
- API client 정책은 명시적 allowlist다. Auth/Onboarding/Schedule legacy raw DTO만 `requestRaw`를 쓰고, Dashboard/Focus/Goals/Tasks/Settings/Sync/Agent 계열은 envelope를 기본값으로 유지한다.

## 다음 우선순위 제안

- Provider write outbox 자동 flush worker와 conflict review UX 확정
- Google OAuth token refresh 자동화와 재연결 UX 정리
- Docker packaging 단계에서 backend/frontend/E2E 실행 래퍼 추가
