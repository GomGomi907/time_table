# Frontend

Next.js App Router 기반 프론트엔드입니다. 현재 제품 기준은 소개형 랜딩이 아니라 작업용 워크스페이스이며, 핵심 경로는 아래와 같습니다.

- `/dashboard`: 오늘 실행 상태와 핵심 운영 카드
- `/schedule`: 주간 시간표 편집과 import
- `/goals`: 목표 보드와 목표 생성/수정
- `/settings`: 엔진 설정, 연동 상태, 운영 정책
- `/auth/callback`: Google 로그인 콜백 처리
- `/mockups/today-workspace`: 내부 참고용 실험 화면

## Scripts

```bash
npm run dev
npm run lint
npm run build
npm run test:e2e
```

기본 개발 서버는 `http://localhost:3000`입니다.

## Notes

- Playwright 산출물과 임시 캡처는 저장소 관리 대상이 아닙니다.
- 화면/기능 기준 문서는 루트의 `DESIGN.md`와 `docs/reboot_*` 문서를 우선합니다.
