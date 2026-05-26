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
  const errorMessage = searchParams.get("message");
  const callbackUrl = searchParams.get("callbackUrl");

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
            ? "아래 진단 정보를 기준으로 OAuth 설정을 바로잡은 뒤 다시 시도해보세요."
            : isMock
            ? "개발용 계정으로 작업 공간을 준비하고 있습니다. 잠시 후 다음 화면으로 이동합니다."
            : "연결된 계정을 확인한 뒤, 필요한 생활 리듬만 짧게 설정합니다."}
        </p>
        {isError ? (
          <div className="inline-message error">
            <strong>OAuth 진단</strong>
            <p>
              {errorReason ? `오류 코드: ${errorReason}` : "오류 코드를 받지 못했습니다."}
              {errorMessage ? ` · ${errorMessage}` : ""}
            </p>
            {callbackUrl ? (
              <p>
                Google Console 승인된 리디렉션 URI에 다음 주소가 정확히 등록되어 있어야 합니다:
                <br />
                <code>{callbackUrl}</code>
              </p>
            ) : null}
          </div>
        ) : null}
        <Link className="ghost-btn link-btn" href="/login">
          로그인 화면으로 돌아가기
        </Link>
      </div>
    </div>
  );
}
