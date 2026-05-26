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
  const isError = status === "error";

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
          <p className="eyebrow">계정 연결</p>
          <h1>일정 비서를 시작할 준비를 하고 있습니다.</h1>
          <p>연결된 계정을 확인한 뒤, 필요한 생활 리듬만 짧게 설정합니다.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="status-screen">
      <div className="status-panel">
        <p className="eyebrow">계정 연결</p>
        <h1>
          {isError
            ? "Google 계정 연결을 완료하지 못했습니다."
            : status === "success"
            ? "Time Table 작업 공간을 준비하고 있습니다."
            : "계정 연결 상태를 확인하고 있습니다."}
        </h1>
        <p>
          {isError
            ? "OAuth 설정, 승인된 리디렉션 URI, Client ID/Secret 값을 확인한 뒤 다시 시도해보세요."
            : isMock
            ? "개발용 계정으로 작업 공간을 준비하고 있습니다. 잠시 후 다음 화면으로 이동합니다."
            : "연결된 계정을 확인한 뒤, 필요한 생활 리듬만 짧게 설정합니다."}
        </p>
        <Link className="ghost-btn link-btn" href="/login">
          로그인 화면으로 돌아가기
        </Link>
      </div>
    </div>
  );
}
