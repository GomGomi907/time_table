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
    window.location.assign(result.url);
  }

  if (session?.authenticated && !onboardingCompleted && (onboardingPhase === "idle" || onboardingPhase === "loading")) {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">초기 세팅 확인</p>
          <h1>AI 일정 조율 온보딩 여부를 확인하고 있습니다.</h1>
          <p>로그인된 세션을 기준으로 바로 온보딩을 시작할지 확인하는 중입니다.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="status-screen">
      <div className="login-panel login-panel-rich">
        <section className="login-hero">
          <p className="eyebrow">Time Table</p>
          <h1>로그인하면 일정을 읽고, 생활 패턴을 물은 뒤 바로 AI 조율을 체험합니다.</h1>
          <p>
            대시보드와 주간 플래너를 같은 세션으로 연결하고, 첫 사용자라면 생활 패턴 몇 가지만
            확인한 뒤 첫 AI 일정 조율안까지 곧바로 보여줍니다.
          </p>

          <div className="login-benefit-list">
            <div className="login-benefit-item">
              <strong>주간 운영</strong>
              <span>시간표 조정, 오늘 흐름 확인, 집중 상태를 한 제품 안에서 봅니다.</span>
            </div>
            <div className="login-benefit-item">
              <strong>AI 온보딩</strong>
              <span>처음 로그인하면 Google 데이터를 조용히 읽고 생활 패턴 기준만 짧게 맞춥니다.</span>
            </div>
            <div className="login-benefit-item">
              <strong>개발 모드 대응</strong>
              <span>Google 자격 증명이 없으면 백엔드가 Mock 로그인으로 자동 안내합니다.</span>
            </div>
          </div>
        </section>

        <section className="login-action-card">
          <p className="panel-kicker">시작하기</p>
          <h2>계정 연결</h2>
          <p>
            로그인 후 세션을 만들고, 필요한 경우 곧바로 AI 일정 조율 온보딩으로 이어집니다.
          </p>

          {loginMessage ? (
            <div className="inline-message info">
              <strong>로그인 안내</strong>
              <p>{loginMessage}</p>
            </div>
          ) : null}

          {sessionPhase === "error" ? (
            <div className="inline-message error">
              <strong>세션 확인 실패</strong>
              <p>{sessionError ?? "백엔드 연결을 확인해 주세요."}</p>
            </div>
          ) : null}

          <div className="guest-actions">
            <button
              className="solid-btn wide-btn"
              disabled={isPending}
              onClick={() => startTransition(() => void handleLogin())}
            >
              {isPending ? "로그인 준비 중..." : "Google로 시작"}
            </button>
            <button className="ghost-btn wide-btn" onClick={() => void refreshSession()}>
              세션 다시 확인
            </button>
          </div>
        </section>
      </div>
    </div>
  );
}
