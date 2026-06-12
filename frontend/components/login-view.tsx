"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";

import { api } from "@/lib/api";
import { BrandLogo } from "@/components/brand-logo";
import { GoogleMark } from "@/components/google-mark";
import { useOnboardingBootstrap } from "@/hooks/use-onboarding-bootstrap";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";

export function LoginView() {
  const router = useRouter();
  const { session, sessionPhase, sessionError, refreshSession } = useSessionBootstrap();
  const { onboardingPhase, needsOnboarding, onboardingCompleted } = useOnboardingBootstrap();
  const [loginMessage, setLoginMessage] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    if (!session?.authenticated) {
      return;
    }

    if (onboardingCompleted) {
      router.replace("/dashboard");
      return;
    }

    if (onboardingPhase === "ready" && needsOnboarding) {
      router.replace("/onboarding");
    }
  }, [router, session?.authenticated, onboardingCompleted, onboardingPhase, needsOnboarding]);

  async function handleLogin() {
    const result = await api.getLoginStart();
    setLoginMessage(result.message);
    if (!result.enabled || !result.url) {
      return;
    }
    window.location.assign(result.url);
  }

  if (session?.authenticated && !onboardingCompleted && (onboardingPhase === "idle" || onboardingPhase === "loading")) {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">작업 공간 준비</p>
          <h1>오늘 일정을 준비합니다.</h1>
          <p>필요한 생활 리듬만 짧게 설정할 수 있습니다.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="status-screen">
      <div className="login-panel login-panel-rich">
        <section className="login-hero">
          <div className="login-brand" aria-label="Time Table">
            <span className="brand-mark logo-mark">
              <BrandLogo />
            </span>
            <p className="eyebrow">Time Table</p>
          </div>
          <h1>오늘 일정과 지금 할 일을 바로 보여줍니다.</h1>
          <p>오늘 일정, 지금 할 일, 주간 일정표만 한 화면에 정리해 보여줍니다.</p>
        </section>

        <section className="login-action-card">
          <p className="panel-kicker">시작</p>
          <h2>시작하기</h2>
          <div className="oauth-disclosure" aria-label="Google 데이터 사용 안내">
            <strong>Google로 계속하면</strong>
            <p>
              이름·이메일로 계정을 만들고, 사용자가 승인한 Calendar/Tasks 데이터만 오늘 일정·할 일 표시와
              승인 기반 일정 반영에 사용합니다. 연결 해제와 계정 삭제는 로그인 후 언제든지 가능합니다.
            </p>
          </div>

          {loginMessage ? (
            <div className="inline-message info">
              <strong>로그인 안내</strong>
              <p>{loginMessage}</p>
            </div>
          ) : null}

          {sessionPhase === "error" ? (
            <div className="inline-message error">
              <strong>세션 확인 실패</strong>
              <p>{sessionError ?? "잠시 후 다시 시도하면 됩니다."}</p>
            </div>
          ) : null}

          <div className="guest-actions">
            <button
              className="google-signin-button wide-btn"
              disabled={isPending}
              onClick={() =>
                startTransition(() => {
                  void handleLogin();
                })
              }
            >
              <GoogleMark />
              <span>{isPending ? "로그인 준비 중..." : "Google로 계속하기"}</span>
            </button>
            {sessionPhase === "error" ? (
              <button className="ghost-btn wide-btn" data-testid="status-retry-action" onClick={() => void refreshSession()}>
                세션 다시 확인
              </button>
            ) : null}
          </div>

          <nav className="legal-links" aria-label="서비스 고지">
            <Link href="/">서비스 소개</Link>
            <Link href="/privacy">개인정보처리방침</Link>
            <Link href="/terms">서비스 약관</Link>
          </nav>
        </section>
      </div>
    </div>
  );
}
