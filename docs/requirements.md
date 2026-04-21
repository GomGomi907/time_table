# Smart Schedule SaaS 요구사항 정의서 (Premium)

## 1. 제품 비전 (Product Vision)
**"The Last Time-Management Tool You'll Ever Need."**
단순한 캘린더가 아닌, 사용자의 맥락을 이해하고 스스로 최적화하는 **AI 기반의 모듈형 생산성 플랫폼**입니다.
사용자는 레고 블록을 조립하듯 자신만의 완벽한 대시보드를 구성할 수 있으며, 이 모든 과정은 AI 에이전트가 보조합니다.

## 2. 핵심 아키텍처: 모듈형 위젯 시스템 (Modular Widget System)
정해진 화면이 없습니다. 모든 것은 **위젯(Widget)**이자 **블록(Block)**입니다.

### 2.1 대시보드 (The Canvas)
*   **Grid Layout**: 반응형 그리드 시스템 위에서 위젯의 위치와 크기(WxH)를 자유롭게 조절.
*   **Multi-Page**: '메인', '집중 모드', '주말 모드' 등 상황별 대시보드 프리셋 생성 가능.
*   **AI Auto-Layout**: "나 개발자인데 알맞게 구성해줘"라고 하면, AI가 적절한 위젯들을 배치해줌.

### 2.2 핵심 위젯 (Core Widgets)
모든 위젯은 개별적인 **설정(Config)**과 **데이터 소스**를 가집니다.
*   **Goal Hierarchy Block**: 1년~1주 목표를 계층적으로 시각화 (Progress Bar / Tree View 옵션).
*   **Universal Timetable Block**:
    *   단순 시간표가 아님. **Routine Layer** 지원.
    *   *AI Feature*: 빈 시간을 분석하여 "딥워크 타임" 자동 제안.
*   **Task Master Block**: Google Tasks, Notion, Jira 등 다양한 소스 통합 가능(초기엔 Google Tasks 집중).
*   **Metric Block**: "이번 주 독서 시간", "수면 시간" 등 사용자가 정의한 지표 추적.
*   **Environment Block**: 날씨, 주식, 뉴스 등 외부 정보 위젯.

## 3. AI 에이전트 시스템 (AI Configuration Agent)
AI는 단순한 채팅 봇이 아닙니다. 시스템의 **Super Admin** 권한을 가진 에이전트입니다.

### 3.1 AI Heartbeat (능동적 상태 추적 및 조율)
*오픈소스 프로젝트 'OpenClo'에서 영감을 받은 핵심 기능입니다.*
*   **주기적 상태 체크**: 백그라운드에서 주기적으로(Heartbeat) LLM을 호출하여 사용자의 현재 작업 상태를 파악합니다.
*   **능동적 개입 및 상호작용**: 
    *   예: "공부하기" 일정이 종료될 시간이 되었을 때, LLM이 시스템 컨텍스트를 파악해 "공부를 다 끝내지 못했는데 시간을 연장할까요, 아니면 여기서 중단하고 다음 일정으로 넘어갈까요?"라고 능동적으로 묻습니다.
    *   사용자의 대답에 따라 즉석에서 일정을 조율하고 뒤이은 스케줄을 자동으로 업데이트합니다.

### 3.2 Configuration Helper
*   **Natural Language Config**: "배경을 좀 더 어둡게 하고, 글씨 키워줘" -> CSS 및 테마 설정 변경.
*   **Layout Assistant**: "할 일 목록이 너무 작아. 키워줘." -> 위젯 사이즈 조정.

### 3.3 Schedule Strategist
*   **Context Aware**: 사용자의 지난주 성과를 분석하여 "이번 주는 목표를 70%로 하향 조정합시다" 제안.
*   **Smart Rescheduling**: 긴급 회의가 생기면, 겹치는 개인 운동 일정을 알아서 "다음 가능한 시간"으로 이동 제안.

## 4. 페이지 및 기능 상세 (Page Specs)

### 4.1 메인 대시보드 (Canvas)
*   사용자 정의형 그리드.
*   우측 하단 (또는 단축키) **AI Command Bar** (Spotlight/Alfred 스타일) 제공.

### 4.2 마켓플레이스 / 라이브러리 (Widget Store)
*   새로운 위젯을 탐색하고 추가하는 공간.
*   커뮤니티 프리셋 ("서울대생 공부법 세팅", "실리콘밸리 개발자 세팅") 다운로드.

### 4.3 설정 및 계정 (Deep Settings)
*   **Integrations**: Google, Calendar, Weather API 등 연결 관리.
*   **Data Export**: "내 인생 데이터"를 CSV/JSON으로 언제든 추출 가능 (신뢰성 확보).

## 5. 기술적 제약 및 품질 목표 (Quality Goals)
*   **Performance**: 대시보드 로딩 < 0.5초. Rust 백엔드의 강력한 성능 활용.
*   **Stability**: 위젯 하나가 에러가 나도 전체 앱은 죽지 않아야 함 (Error Boundary 철저).
*   **Design**: 다크 모드, 글래스모피즘(Glassmorphism) 등 최신 트렌드 적용. 200억 가치를 증명하는 UI 퀄리티.
