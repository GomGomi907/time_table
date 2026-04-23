# Frontend Backend Gap Memo

Date: 2026-04-23

## 이번 구현에서 연결한 범위

- 새 Next.js App Router 프론트를 `frontend/`에 다시 구성했다.
- `dashboard`, `schedule`, `focus`, `login`, `auth/callback` 화면을 백엔드 API에 연결했다.
- 세션 확인과 CSRF 쿠키 처리 후 보호된 POST 요청을 보내도록 프론트 API 레이어를 만들었다.
- 일정 블록 생성, 포커스 완료/연장/미루기, reschedule suggestion 생성/적용/거절까지 최소 액션을 연결했다.

## 현재 확인된 백엔드 의존 / 부족한 점

- `GET /api/focus/current`는 일정 블록이 아니라 이벤트와 태스크를 기준으로 현재 포커스를 계산한다.
- 그래서 기본 seed 시간표만 있는 초기 상태에서는 포커스 화면이 비어 보일 수 있어, 프론트에서 시간표 기반 fallback 안내를 추가했다.
- `GET /api/sync/status`와 관련 수동 sync API는 현재 scaffold 성격이 강하다.
- 프론트는 상태를 표시하지만, 실제 외부 provider diff 반영까지 끝난 상태로 가정하면 안 된다.
- `POST /api/agent/reschedule`와 suggestion apply flow도 MVP 수준이다.
- 프론트는 이 흐름을 그대로 노출했지만, 복잡한 재배치 계산 결과를 기대하는 UX로 확장하려면 백엔드 설명/상세 payload가 더 필요하다.
- 대시보드 집계용 전용 API가 아직 없다.
- 현재 완료율, 주간 구성도, 상위 목표 같은 값은 프론트에서 기존 응답을 조합해 계산한다.
- 온보딩 완료 여부를 영구 저장하는 백엔드 필드가 아직 없다.
- 현재 프론트는 첫 사용자 초기 세팅 완료를 `localStorage` 기준으로 기억한다.
- 그래서 브라우저나 기기를 바꾸면 초기 세팅 화면이 한 번 더 보일 수 있다.
- 출근 시각, 집중 시간대 같은 생활 패턴 답변을 저장할 백엔드 필드가 아직 없다.
- 현재 프론트는 `취침/기상`만 `settings`에 반영하고, 나머지 루틴 답변은 사용자별 `localStorage`에 저장해 온보딩 AI 제안 생성에만 사용한다.

## 다음 우선순위 제안

- 대시보드 전용 aggregate endpoint 추가
- 포커스 상태에 schedule block 기반 보조 정보 포함 여부 결정
- suggestion payload를 프론트 친화적으로 요약한 DTO 추가
- sync/scaffold 상태를 구분하는 명시적 필드 추가
- 사용자별 onboarding completed 필드 또는 API 추가
