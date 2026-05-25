# Time Table

Time Table은 주간 시간표, 할 일, 목표, 집중 실행을 하나의 화면 경험으로 묶어주는 실행형 생산성 워크스페이스입니다.

대부분의 생산성 도구는 일정은 캘린더에, 할 일은 태스크 앱에, 목표는 노트에 흩어져 있습니다. Time Table은 그 분산된 맥락을 다시 하나로 묶어, “이번 주를 어떻게 설계하고 오늘 무엇을 실행할지”를 더 선명하게 보여주는 것을 목표로 합니다.

## 한눈에 보기

- 오늘 해야 할 일과 이번 주 시간 배치를 한 화면에서 확인할 수 있습니다.
- 시간표, 할 일, 목표가 서로 연결된 상태로 작동합니다.
- 집중 화면을 통해 지금 수행해야 하는 항목에 바로 들어갈 수 있습니다.
- Google 캘린더와 할 일 연동을 통해 기존 도구와 자연스럽게 연결됩니다.
- AI는 직접 변경하지 않고, 제안과 조정 보조 역할에 집중합니다.

## 이런 프로젝트입니다

Time Table은 단순한 캘린더 앱이 아닙니다.  
“시간을 채우는 도구”보다 “실행을 운영하는 도구”에 가깝습니다.

사용자는 이 프로젝트를 통해 다음과 같은 경험을 얻는 것을 목표로 합니다.

- 오늘 지금 무엇을 해야 하는지 바로 파악한다.
- 주간 시간표와 실제 할 일을 분리하지 않고 관리한다.
- 목표를 일정과 연결해 진짜 진행률로 느낀다.
- 계획과 실행 사이의 전환 비용을 줄인다.

## 핵심 경험

### 통합 워크스페이스

대시보드에서 오늘 상태, 주간 보드, 할 일 요약, 목표 진행, 동기화 상태, AI 제안을 한 번에 확인할 수 있습니다.

### 집중 실행 화면

현재 해야 할 일과 다음 전환 시점을 더 선명하게 보여주는 집중 전용 화면을 제공합니다.

### 연결된 계획 구조

일정, 할 일, 목표가 서로 분리된 엔티티가 아니라 하나의 운영 체계 안에서 연결되도록 설계되어 있습니다.

### 제안 중심 AI

AI는 백그라운드에서 사용자를 대신해 멋대로 변경하지 않습니다. 구조화된 제안과 재배치 보조를 통해 사용자의 판단을 돕는 방향으로 동작합니다.

## 현재 포함된 범위

현재 저장소에는 출시형 MVP의 1차 구현이 반영되어 있습니다.

- Spring Boot 기반 백엔드 API
- Next.js 기반 프론트엔드 워크스페이스
- 이벤트, 할 일, 목표, 집중 실행, 동기화, 제안 기능의 기본 구조
- 로컬 개발용 OAuth 및 작업 자산 분리 구조

## 기술 구성

- Frontend: React 기반 Next.js App Router, TypeScript, Zustand
- Backend: Spring Boot, Spring Data JPA, Flyway
- Database: 현재 로컬 개발과 테스트는 H2, 운영 환경은 PostgreSQL 전환을 염두에 둔 구조
- Integration: Google Calendar, Google Tasks
- LLM: Google Gemini API 기반 일정 정규화와 재조율 제안
- Deployment: Docker

## 빠른 시작

백엔드 실행:

```bash
cd backend
./gradlew.bat bootRun
```

프론트엔드 실행:

```bash
cd frontend
npm run dev
```

기본 주소:

- 프론트엔드: `http://localhost:3000`
- 백엔드: `http://localhost:8080`

컨테이너 실행:

```bash
cp .env.example .env
# .env에서 POSTGRES_PASSWORD와 Google OAuth 값을 실제 값으로 교체합니다.
docker compose up --build
```

AI 제안을 실제 Gemini API로 사용하려면 루트 `.env`에 Gemini API 키를 넣고 `APP_AI_ENABLED=true`를 켭니다.

```bash
APP_AI_ENABLED=true
APP_GEMINI_API_KEY=...
APP_AI_MODEL=gemini-2.5-flash
```

서비스 배포 기본값은 개발용 Mock 로그인을 노출하지 않도록 닫혀 있습니다.
로컬 E2E나 데모에서만 `.env`에 `APP_AUTH_MOCK_LOGIN_ENABLED=true`와
`APP_SYNC_GOOGLE_MOCK_ENABLED=true`를 명시적으로 켜세요.

## 작업공간 구조

```text
Time_table/
├─ backend/      Spring Boot API와 도메인 로직
├─ frontend/     Next.js UI와 사용자 워크스페이스
├─ gemma4/       이전 로컬 LLM 실험 자산
├─ .local/       로컬 전용 문서와 시크릿
└─ README.md
```

`.local/`은 버전 관리 대상이 아닌 로컬 전용 작업물을 모아두는 공간입니다.  
기획 문서, 내부 가이드, 시크릿 파일은 이 영역에서 관리합니다.

## 프로젝트 방향

Time Table은 앞으로 아래 방향으로 계속 다듬어질 예정입니다.

- 더 살아 있는 주간 워크스페이스 경험
- 더 자연스러운 집중 상태 전환
- 더 믿을 수 있는 동기화 상태와 반영 절차
- 더 실용적인 AI 제안 품질
- 컨테이너 기반 운영 및 배포 절차 정비

## 한마디로

Time Table은 “계획을 적는 도구”보다 “한 주를 운영하는 화면”에 더 가깝습니다.

캘린더, 태스크, 목표, 집중을 따로 보지 않고 하나의 실행 체계로 다루고 싶을 때, 이 프로젝트가 그 기반이 되는 것을 목표로 합니다.
