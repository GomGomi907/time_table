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
  const isTokenExchangeError =
    errorMessage?.includes("invalid_token_response") || errorMessage?.includes("401 Unauthorized");

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
            ? "로그인을 완료하지 못했습니다."
            : status === "success"
            ? "오늘 일정을 준비하고 있습니다."
            : "잠시만 기다려 주세요."}
        </h1>
        <p>
          {isError
            ? "아래 오류를 확인한 뒤 다시 시도해보세요."
            : isMock
            ? "개발용 계정으로 작업 공간을 준비하고 있습니다."
            : "필요한 생활 리듬만 짧게 설정합니다."}
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
            {isTokenExchangeError ? (
              <p>
                지금 오류는 인증 코드를 받은 뒤 토큰 교환에서 실패한 상태입니다. Cloud Run의
                <code> GOOGLE_CLIENT_ID </code>와 <code> GOOGLE_CLIENT_SECRET </code>이 같은 OAuth 웹
                클라이언트의 값인지, Secret 값에 따옴표·공백·다른 API 키가 들어가지 않았는지 확인해보세요.
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
