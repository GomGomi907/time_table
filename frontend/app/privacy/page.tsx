import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "개인정보처리방침 | Time Table",
  description: "Time Table의 개인정보 및 Google 사용자 데이터 처리 방침입니다.",
};

const GOOGLE_USER_DATA_POLICY_URL = "https://developers.google.com/terms/api-services-user-data-policy";
const SUPPORT_EMAIL = "tkdrmsdl90715@gmail.com";

export default function PrivacyPage() {
  return (
    <main className="legal-shell">
      <article className="legal-page surface-card">
        <header className="legal-header">
          <p className="eyebrow">Time Table</p>
          <h1>개인정보처리방침</h1>
          <p>
            시행일: <time dateTime="2026-06-12">2026년 6월 12일</time>
          </p>
        </header>

        <section className="legal-section">
          <h2>1. 방침의 목적</h2>
          <p>
            Time Table은 사용자의 주간 계획, 오늘 일정, 할 일, 집중 실행 흐름을 정리하는 일정 운영
            서비스입니다. 본 방침은 Time Table이 서비스 제공 과정에서 어떤 개인정보와 Google 사용자
            데이터를 수집·이용·보관·공유하는지 설명합니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>2. 수집하는 정보</h2>
          <ul className="legal-list">
            <li>계정 정보: Google 로그인으로 제공되는 이름, 이메일 주소, 프로필 식별자</li>
            <li>일정 및 할 일 정보: 사용자가 승인한 Google Calendar 일정, Google Tasks 항목, 서비스 내에서 작성한 일정·목표·메모</li>
            <li>AI 요청 정보: 사용자가 입력한 일정 조정 요청, 승인·거절한 AI 제안, 서비스가 생성한 일정 재배치 초안</li>
            <li>기술 정보: 로그인·동기화 기록, 오류 로그, 보안 감사 로그, 기기·브라우저 환경 정보</li>
          </ul>
        </section>

        <section className="legal-section">
          <h2>3. 이용 목적</h2>
          <ul className="legal-list">
            <li>사용자 계정 생성, 로그인 유지, 접근 권한 확인</li>
            <li>오늘 일정, 주간 일정표, 할 일, 집중 실행 화면 제공</li>
            <li>사용자가 승인한 Google Calendar/Tasks 동기화 및 일정 반영</li>
            <li>사용자가 요청한 AI 일정 재배치 초안 생성과 승인 이력 관리</li>
            <li>서비스 안정성, 오류 대응, 보안 사고 예방, 고객 지원</li>
          </ul>
        </section>

        <section className="legal-section">
          <h2>4. Google 사용자 데이터 처리</h2>
          <p>
            Time Table은 사용자가 Google OAuth 동의 화면에서 명시적으로 승인한 범위 안에서만 Google
            사용자 데이터에 접근합니다. Google Calendar/Tasks 데이터는 사용자의 일정 운영 기능을 제공하기
            위한 목적으로만 사용되며, 광고 타겟팅·판매·감시 목적 또는 서비스 기능과 무관한 제3자 제공에
            사용하지 않습니다.
          </p>
          <p>
            Time Table의 Google 사용자 데이터 사용 및 이전은{" "}
            <a href={GOOGLE_USER_DATA_POLICY_URL} rel="noreferrer" target="_blank">
              Google API Services User Data Policy
            </a>
            와 Limited Use 요구사항을 준수합니다. 필요한 최소 권한만 요청하며, 새로운 목적이나 범위로
            Google 사용자 데이터를 사용해야 하는 경우에는 방침을 갱신하고 필요한 동의를 다시 요청합니다.
          </p>
          <p>
            Google 사용자 데이터는 일반화된 AI/ML 모델을 학습·개선하는 데 사용하지 않습니다. 사용자가 AI 일정
            재배치 초안을 요청한 경우에 한해 필요한 일정 맥락과 요청 문구를 최소 범위로 처리하며, 제안은
            사용자의 확인과 승인 전에는 외부 캘린더에 반영하지 않습니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>5. 보관 및 삭제</h2>
          <ul className="legal-list">
            <li>서비스 제공에 필요한 정보는 계정 유지 기간 동안 보관합니다.</li>
            <li>Google 연결 해제를 실행하면 저장된 Google OAuth 토큰과 권한 상태를 삭제하고 새 동기화·반영을 중지합니다.</li>
            <li>계정·데이터 삭제를 실행하면 법령상 보관이 필요한 정보를 제외하고 서비스 계정, 일정·할 일·목표, 동기화 기록, AI 요청 기록을 삭제합니다.</li>
            <li>보안·오류 대응 로그는 필요한 기간 동안만 보관하며 기본 운영 기준은 최대 90일입니다.</li>
          </ul>
        </section>

        <section className="legal-section">
          <h2>6. 공유 및 위탁</h2>
          <p>
            Time Table은 서비스 운영에 필요한 범위에서 Google Cloud Run, Cloud SQL, Secret Manager,
            Google OAuth, Google Calendar API, Google Tasks API, Google Gemini API 등 인프라·인증·동기화·AI
            처리 제공자에게 처리를 위탁할 수 있습니다. 법령상 요구, 보안 사고 대응, 사용자의 명시적 동의가
            있는 경우를 제외하고 개인정보나 Google 사용자 데이터를 판매하지 않습니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>7. 보안 조치</h2>
          <p>
            Time Table은 접근 권한 제한, 전송 구간 암호화, 토큰 보호, 감사 로그, 최소 권한 원칙을 통해
            개인정보와 Google 사용자 데이터를 보호합니다. 다만 인터넷 기반 서비스의 특성상 모든 위험을
            완전히 제거할 수는 없으므로, 보안 취약점이나 의심 활동을 발견하면 즉시 운영자에게 알려 주세요.
          </p>
        </section>

        <section className="legal-section">
          <h2>8. 이용자의 권리</h2>
          <p>
            사용자는 자신의 개인정보 열람, 정정, 삭제, 처리 정지, Google 연결 해제를 요청할 수 있습니다.
            로그인 후 계정 관리 영역에서 Google 연결 해제와 계정·데이터 삭제를 직접 실행할 수 있으며,
            지원이 필요한 경우{" "}
            <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>로 요청할 수 있습니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>9. 문의</h2>
          <p>
            개인정보, Google 사용자 데이터, 계정 삭제, 보안 취약점 신고는{" "}
            <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>로 연락해 주세요.
          </p>
        </section>

        <section className="legal-section">
          <h2>10. 변경</h2>
          <p>
            본 방침은 서비스 기능, 법령, Google 정책 변경에 따라 갱신될 수 있습니다. 중요한 변경이 있는 경우
            서비스 화면 또는 별도 안내를 통해 고지합니다.
          </p>
        </section>

        <footer className="legal-footer">
          <Link className="ghost-btn link-btn" href="/">
            서비스 소개
          </Link>
          <Link className="ghost-btn link-btn" href="/terms">
            서비스 약관
          </Link>
        </footer>
      </article>
    </main>
  );
}
