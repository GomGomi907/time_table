"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect } from "react";

import { useOnboardingBootstrap } from "@/hooks/use-onboarding-bootstrap";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";

export function AuthCallbackView() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { session, refreshSession } = useSessionBootstrap();
  const { onboardingPhase, needsOnboarding, onboardingCompleted } = useOnboardingBootstrap();
  const status = searchParams.get("status");
  const isMock = searchParams.get("mock") === "true";

  useEffect(() => {
    void refreshSession();
  }, []);

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

  if (session?.authenticated && !onboardingCompleted && (onboardingPhase === "idle" || onboardingPhase === "loading")) {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">로그인 콜백</p>
          <h1>AI 일정 조율 온보딩을 준비하고 있습니다.</h1>
          <p>세션 복구 후 바로 일정 가져오기 흐름으로 보낼지 확인하는 중입니다.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="status-screen">
      <div className="status-panel">
        <p className="eyebrow">로그인 콜백</p>
        <h1>
          {status === "success"
            ? "로그인 결과를 반영하고 있습니다."
            : "로그인 상태를 확인 중입니다."}
        </h1>
        <p>
          {isMock
            ? "Mock 로그인으로 세션을 만들었습니다. 잠시 후 AI 온보딩 또는 대시보드로 이동합니다."
            : "OAuth 콜백 결과를 반영하는 중입니다. 세션 확인 후 AI 온보딩 또는 대시보드로 이동합니다."}
        </p>
        <Link className="ghost-btn link-btn" href="/login">
          로그인 화면으로 돌아가기
        </Link>
      </div>
    </div>
  );
}
