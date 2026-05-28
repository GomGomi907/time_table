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
  const errorReason = searchParams.get("reason");

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
          <p className="eyebrow">로그인</p>
          <h1>오늘 일정을 준비하고 있습니다.</h1>
          <p>필요한 생활 리듬만 짧게 설정합니다.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="status-screen">
      <div className="status-panel">
        <p className="eyebrow">로그인</p>
        <h1>
          {isError
            ? "로그인을 다시 시도해 주세요."
            : status === "success"
            ? "오늘 일정을 준비하고 있습니다."
            : "잠시만 기다려 주세요."}
        </h1>
        <p>
          {isError
            ? "인증이 완료되지 않았습니다. 잠시 후 다시 시도하면 됩니다."
            : isMock
            ? "개발용 계정으로 작업 공간을 준비하고 있습니다."
            : "필요한 생활 리듬만 짧게 설정합니다."}
        </p>
        {isError ? (
          <div className="inline-message error">
            <strong>로그인 안내</strong>
            <p>{errorReason ? "로그인 요청이 만료되었거나 취소되었습니다." : "세션을 만들지 못했습니다."}</p>
          </div>
        ) : null}
        <Link className="ghost-btn link-btn" href="/login">
          로그인 화면으로 돌아가기
        </Link>
      </div>
    </div>
  );
}
