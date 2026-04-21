# Google Calendar 연동 가이드

Smart Schedule 시스템과 Google Calendar를 실시간으로 연동하기 위한 설정 방법입니다.
아래 절차를 따라 Google Cloud Platform(GCP)에서 키를 발급받고 `.env` 파일에 입력해주세요.

## 1. Google Cloud 프로젝트 생성
1. [Google Cloud Console](https://console.cloud.google.com/)에 접속합니다.
2. 좌측 상단 프로젝트 선택 드롭다운에서 **"새 프로젝트"**를 클릭합니다.
   - 이름 예시: `Smart-Schedule-Dev`
3. 프로젝트가 생성되면 선택합니다.

## 2. Google Calendar API 활성화
1. 좌측 메뉴에서 **"API 및 서비스" > "라이브러리"**로 이동합니다.
2. 검색창에 `Google Calendar API`를 검색합니다.
3. **Google Calendar API**를 클릭하고 **"사용"**을 누릅니다.

## 3. OAuth 동의 화면 구성
1. 좌측 메뉴에서 **"API 및 서비스" > "OAuth 동의 화면"**으로 이동합니다.
2. User Type: **"외부(External)"** 선택 후 "만들기".
3. 앱 정보 입력:
   - 앱 이름: `Smart Schedule`
   - 사용자 지원 이메일: 본인 이메일
   - 개발자 연락처 정보: 본인 이메일
4. 저장 후 계속 -> **"범위(Scopes)"** 단계:
   - "범위 추가 또는 삭제" 클릭.
   - `.../auth/calendar.readonly` 또는 `.../auth/calendar` 검색 후 체크.
   - "업데이트" 후 "저장 후 계속".
5. **테스트 사용자 (Test Users)**:
   - **중요**: 앱이 게시되기 전까지는 테스트 사용자만 로그인 가능합니다.
   - "ADD USERS" 클릭 후 **본인의 구글 이메일**을 추가하세요.

## 4. 자격 증명 (Credentials) 생성
1. 좌측 메뉴에서 **"자격 증명"**으로 이동합니다.
2. 상단 **"자격 증명 만들기" > "OAuth 클라이언트 ID"** 선택.
3. 애플리케이션 유형: **"웹 애플리케이션"**.
4. 이름: `Smart Schedule Web`
5. **승인된 리디렉션 URI (Authorized redirect URIs)**:
   - **중요**: 아래 주소를 정확히 추가해야 합니다.
   - `http://localhost:3000/auth/callback`
   (+ 백엔드 직접 테스트용 `http://localhost:8080/api/auth/google/callback` 도 추가 가능하지만, 현재 프론트엔드 흐름상 3000번이 필수입니다)
6. "만들기" 클릭.

## 5. 키 확인 및 적용
팝업창에 뜨는 **클라이언트 아이디(Client ID)**와 **클라이언트 보안 비밀(Client Secret)**을 복사합니다.

### `.env` 파일 설정
`backend/.env` 파일을 열고 다음과 같이 수정합니다:

```env
DATABASE_URL=sqlite:mvp.db
RUST_LOG=backend=debug,tower_http=debug

# 아래 정보를 실제 값으로 변경해주세요
GOOGLE_CLIENT_ID=여기에_복사한_클라이언트_ID_붙여넣기
GOOGLE_CLIENT_SECRET=여기에_복사한_클라이언트_Secret_붙여넣기
GOOGLE_REDIRECT_URI=http://localhost:3000/auth/callback
```
