"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";

import { api } from "@/lib/api";
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
          <p className="eyebrow">Time Table</p>
          <h1>오늘 일정과 지금 할 일을 바로 보여줍니다.</h1>
          <p>오늘 일정, 지금 할 일, 주간 일정표만 한 화면에 정리해 보여줍니다.</p>
        </section>

        <section className="login-action-card">
          <p className="panel-kicker">시작</p>
          <h2>시작하기</h2>

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
              className="solid-btn wide-btn"
              disabled={isPending}
              onClick={() =>
                startTransition(() => {
                  void handleLogin();
                })
              }
            >
              {isPending ? "로그인 준비 중..." : "Google로 시작"}
            </button>
            <button className="ghost-btn wide-btn" onClick={() => void refreshSession()}>
              다시 시도
            </button>
          </div>
        </section>
      </div>
    </div>
  );
}
