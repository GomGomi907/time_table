"use client";

import { Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { apiClient } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { AuthSessionResponse } from "@/shared/api/types";

interface AuthCallbackClientProps {
  statusParam: string | null;
}

const MAX_RETRIES = 3;
const RETRY_DELAY_MS = 1500;

export const AuthCallbackClient = ({ statusParam }: AuthCallbackClientProps) => {
  const router = useRouter();
  const [retryCount, setRetryCount] = useState(0);
  const [status, setStatus] = useState(
    statusParam === "success"
      ? "로그인 정보를 확인하고 있습니다."
      : "인증 상태를 확인하고 있습니다."
  );
  const [isFailed, setIsFailed] = useState(false);

  useEffect(() => {
    let active = true;
    let retryTimer: number | undefined;

    const scheduleRetry = (message: string) => {
      if (!active || retryCount >= MAX_RETRIES - 1) {
        setIsFailed(true);
        setStatus("세션을 유지하지 못했습니다. 다시 로그인해 주세요.");
        return;
      }

      const nextRetry = retryCount + 1;
      setStatus(`${message} (${nextRetry}/${MAX_RETRIES})`);
      retryTimer = window.setTimeout(() => {
        if (active) {
          setRetryCount(nextRetry);
        }
      }, RETRY_DELAY_MS);
    };

    const confirmSession = async () => {
      try {
        const { data } = await apiClient.get<AuthSessionResponse>("/auth/session");
        if (!active) {
          return;
        }

        if (data.authenticated) {
          setStatus("로그인 확인이 완료되었습니다. 대시보드로 이동합니다.");
          window.setTimeout(() => {
            if (active) {
              router.replace("/dashboard");
            }
          }, 450);
          return;
        }

        if (statusParam === "success") {
          scheduleRetry("인증을 마무리하는 중입니다. 잠시만 기다려 주세요.");
          return;
        }

        setIsFailed(true);
        setStatus("인증 정보를 찾지 못했습니다. 다시 로그인해 주세요.");
      } catch (error) {
        if (!active) {
          return;
        }

        const message = getApiErrorMessage(error, "로그인 상태를 확인하는 중 오류가 발생했습니다.");
        const statusCode =
          typeof error === "object" &&
          error !== null &&
          "response" in error &&
          typeof (error as { response?: { status?: number } }).response?.status === "number"
            ? (error as { response?: { status?: number } }).response?.status
            : undefined;

        if (statusCode === 401 && statusParam === "success") {
          scheduleRetry("인증을 마무리하는 중입니다. 잠시만 기다려 주세요.");
          return;
        }

        setIsFailed(true);
        setStatus(message);
      }
    };

    void confirmSession();

    return () => {
      active = false;
      if (retryTimer !== undefined) {
        window.clearTimeout(retryTimer);
      }
    };
  }, [retryCount, router, statusParam]);

  return (
    <main className="page-shell min-h-screen py-3 sm:py-5">
      <div className="flex min-h-[calc(100vh-1.5rem)] items-center justify-center sm:min-h-[calc(100vh-2.5rem)]">
        <section className="surface-card w-full max-w-md p-8 text-center sm:p-10">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-[rgba(245,92,18,0.1)] text-[var(--accent)]">
            <Loader2 className="h-7 w-7 animate-spin" />
          </div>

          <p className="metric-label mt-6">로그인 확인</p>
          <h1 className="mt-3 text-3xl font-extrabold tracking-tight text-[var(--foreground)]">
            로그인 상태 확인
          </h1>
          <p className="mt-4 text-base leading-7 text-[var(--foreground-muted)]">{status}</p>

          {isFailed && (
            <div className="mt-8 flex justify-center">
              <button type="button" onClick={() => router.replace("/login")} className="btn-primary">
                다시 로그인하기
              </button>
            </div>
          )}
        </section>
      </div>
    </main>
  );
};
