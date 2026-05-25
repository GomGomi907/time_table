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
          <h1>일정 비서를 시작할 준비를 하고 있습니다.</h1>
          <p>연결된 계정을 확인한 뒤, 필요한 생활 리듬만 짧게 설정합니다.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="status-screen">
      <div className="login-panel login-panel-rich">
        <section className="login-hero">
          <p className="eyebrow">Time Table</p>
          <h1>오늘 예정된 일정부터 알려드리는 일정 비서.</h1>
          <p>
            Google 캘린더와 할 일을 불러와 오늘 일정, 다음 행동, 확인할 조정안을 먼저 보여드립니다.
            바꿀 일은 승인하기 전까지 반영하지 않습니다.
          </p>

          <div className="login-briefing-preview" aria-label="오늘 브리핑 미리보기">
            <span>
              <b>오늘 브리핑</b>
              일정 수 · 다음 일정 · 확인할 조정안
            </span>
            <span>
              <b>변경 원칙</b>
              승인 전에는 그대로 유지
            </span>
          </div>

          <div className="login-benefit-list">
            <div className="login-benefit-item">
              <strong>오늘 예정된 일정부터 확인</strong>
              <span>로그인하면 오늘 일정 개수, 다음 일정, 확인할 조정안을 먼저 보여드립니다.</span>
            </div>
            <div className="login-benefit-item">
              <strong>승인 전에는 바꾸지 않음</strong>
              <span>조정안은 설명과 함께 대기하며, 적용/보류는 사용자가 결정합니다.</span>
            </div>
            <div className="login-benefit-item">
              <strong>Google 상태를 숨기지 않음</strong>
              <span>읽기 전용, 재연결, 반영 대기처럼 실제 연결 상태를 그대로 표시합니다.</span>
            </div>
          </div>
        </section>

        <section className="login-action-card">
          <p className="panel-kicker">서비스 시작</p>
          <h2>Google 계정 연결</h2>
          <p>
            캘린더와 할 일을 읽어 오늘 브리핑을 준비합니다. 쓰기 권한이 필요한 경우에도
            적용 전 확인 단계를 거칩니다.
          </p>

          <div className="service-boundary-card" aria-label="서비스 동작 범위">
            <span>읽어오는 것: 일정, 할 일, 연결 상태</span>
            <span>바뀌는 것: 사용자가 승인한 조정안만</span>
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
              <p>{sessionError ?? "서비스 연결을 확인해 주세요."}</p>
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
              연결 상태 확인
            </button>
          </div>
        </section>
      </div>
    </div>
  );
}
