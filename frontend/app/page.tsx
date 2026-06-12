import type { Metadata } from "next";
import Link from "next/link";

import { BrandLogo } from "@/components/brand-logo";

export const metadata: Metadata = {
  title: "Time Table | 일정 운영 워크스페이스",
  description: "Google Calendar와 할 일을 연결해 주간 계획과 오늘 실행을 한 화면에서 관리합니다.",
};

const FEATURES = [
  {
    title: "Google 일정 동기화",
    description: "승인한 Google Calendar/Tasks 데이터만 불러와 오늘 일정과 주간 흐름을 한 화면에서 확인합니다.",
  },
  {
    title: "승인 우선 AI 제안",
    description: "AI가 일정 재배치 초안을 제안하더라도 사용자가 확인하고 승인한 뒤에만 실행하도록 설계했습니다.",
  },
  {
    title: "집중 실행 화면",
    description: "지금 해야 할 일, 다음 일정, 남은 시간을 분리해 하루 운영에 필요한 정보만 보여줍니다.",
  },
];

export default function HomePage() {
  return (
    <main className="public-shell">
      <section className="public-hero surface-card">
        <div className="public-brand">
          <span className="brand-mark public-logo-mark">
            <BrandLogo />
          </span>
          <div>
            <p className="eyebrow">Time Table</p>
            <p className="brand-sub">일정 운영 워크스페이스</p>
          </div>
        </div>

        <div className="public-hero-copy">
          <h1>주간 계획, 오늘 실행, Google 일정 동기화를 한 화면에서 운영합니다.</h1>
          <p>
            Time Table은 사용자가 승인한 Google 일정과 할 일 데이터를 바탕으로 오늘의 우선순위, 주간 일정표,
            집중 실행 흐름을 정리해 주는 개인 일정 운영 서비스입니다.
          </p>
        </div>

        <div className="guest-actions">
          <Link className="solid-btn link-btn" href="/login">
            로그인 화면으로 이동
          </Link>
          <Link className="ghost-btn link-btn" href="/privacy">
            개인정보처리방침
          </Link>
          <Link className="ghost-btn link-btn" href="/terms">
            서비스 약관
          </Link>
        </div>
        <p className="public-oauth-note">
          Google 로그인은 이름·이메일 확인과 사용자가 승인한 Calendar/Tasks 연동에만 사용됩니다.
          연결 해제와 계정·데이터 삭제는 로그인 후 계정 관리 영역에서 직접 처리할 수 있습니다.
        </p>
      </section>

      <section className="public-feature-grid" aria-label="핵심 기능">
        {FEATURES.map((feature) => (
          <article className="surface-card public-feature-card" key={feature.title}>
            <p className="panel-kicker">Feature</p>
            <h2>{feature.title}</h2>
            <p>{feature.description}</p>
          </article>
        ))}
      </section>
    </main>
  );
}
