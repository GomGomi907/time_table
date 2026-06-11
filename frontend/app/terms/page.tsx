import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "서비스 약관 | Time Table",
  description: "Time Table 서비스 이용 약관입니다.",
};

export default function TermsPage() {
  return (
    <main className="legal-shell">
      <article className="legal-page surface-card">
        <header className="legal-header">
          <p className="eyebrow">Time Table</p>
          <h1>서비스 약관</h1>
          <p>
            시행일: <time dateTime="2026-06-11">2026년 6월 11일</time>
          </p>
        </header>

        <section className="legal-section">
          <h2>1. 약관의 적용</h2>
          <p>
            본 약관은 Time Table 서비스의 웹 애플리케이션, Google 연동, 일정·할 일 관리, AI 일정 제안
            기능 이용에 적용됩니다. 서비스를 이용하면 본 약관과{" "}
            <Link href="/privacy">개인정보처리방침</Link>에 동의한 것으로 봅니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>2. 서비스 내용</h2>
          <ul className="legal-list">
            <li>Google Calendar/Tasks 또는 서비스 내 입력 데이터를 기반으로 한 일정·할 일 보기</li>
            <li>오늘 일정, 주간 일정표, 집중 실행 화면 제공</li>
            <li>사용자가 입력한 요청에 따른 AI 일정 재배치 초안 생성</li>
            <li>사용자 승인에 기반한 일정 변경 또는 Google 제공자 동기화</li>
          </ul>
        </section>

        <section className="legal-section">
          <h2>3. 계정과 Google 연결</h2>
          <p>
            사용자는 본인에게 권한이 있는 Google 계정만 연결해야 합니다. Google 연결을 해제하면 일부 일정
            동기화 기능이 제한될 수 있습니다. 사용자는 자신의 계정, 브라우저, 기기 보안 유지에 책임이 있습니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>4. AI 제안의 성격</h2>
          <p>
            Time Table의 AI 기능은 일정 운영을 돕는 초안을 생성합니다. AI 제안은 자동 확정이 아니며, 사용자가
            내용을 확인하고 승인한 뒤에만 서비스 내 일정 반영 또는 외부 캘린더 반영을 진행해야 합니다. 중요한
            일정, 법률·의료·금융·안전 관련 판단은 사용자가 별도로 검토해야 합니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>5. 사용자의 의무</h2>
          <ul className="legal-list">
            <li>허위 정보, 타인의 개인정보, 권한 없는 계정 또는 데이터를 입력하지 않아야 합니다.</li>
            <li>서비스 보안, 인증, 동기화, API 제한을 우회하거나 과도한 자동 요청을 보내서는 안 됩니다.</li>
            <li>불법, 침해, 차별, 괴롭힘, 스팸, 악성 코드 배포 목적의 사용을 금지합니다.</li>
            <li>Google 및 기타 제3자 서비스의 약관과 정책을 준수해야 합니다.</li>
          </ul>
        </section>

        <section className="legal-section">
          <h2>6. 데이터와 콘텐츠</h2>
          <p>
            사용자가 입력하거나 연결한 일정·할 일·메모의 권리는 사용자에게 있습니다. 사용자는 서비스 제공에
            필요한 범위에서 Time Table이 해당 데이터를 저장, 처리, 표시, 동기화할 수 있는 권한을 부여합니다.
            개인정보 및 Google 사용자 데이터의 자세한 처리 기준은 개인정보처리방침을 따릅니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>7. 서비스 변경과 중단</h2>
          <p>
            Time Table은 기능 개선, 보안 조치, 외부 API 정책 변경, 장애 대응을 위해 서비스를 변경하거나
            일시 중단할 수 있습니다. 중요한 변경은 가능한 범위에서 사전에 안내합니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>8. 책임 제한</h2>
          <p>
            Time Table은 일정 운영을 돕는 도구이며, 사용자의 모든 일정 충돌, 지연, 데이터 입력 오류, 외부
            서비스 장애를 보장하지 않습니다. 서비스의 고의 또는 중대한 과실이 아닌 한, 사용자의 독자적 판단
            또는 제3자 서비스 장애로 인한 손해에 대해 책임을 제한합니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>9. 해지</h2>
          <p>
            사용자는 언제든지 서비스 이용을 중단하고 Google 연결을 해제할 수 있습니다. Time Table은 약관 위반,
            보안 위험, 법령상 필요가 있는 경우 서비스 접근을 제한하거나 계정을 종료할 수 있습니다.
          </p>
        </section>

        <section className="legal-section">
          <h2>10. 문의</h2>
          <p>
            서비스 이용, 데이터 처리, 약관에 관한 문의는 서비스 운영자 또는 Google OAuth 동의 화면에 표시되는
            사용자 지원 이메일로 연락해 주세요.
          </p>
        </section>

        <footer className="legal-footer">
          <Link className="ghost-btn link-btn" href="/">
            서비스 소개
          </Link>
          <Link className="ghost-btn link-btn" href="/privacy">
            개인정보처리방침
          </Link>
        </footer>
      </article>
    </main>
  );
}
