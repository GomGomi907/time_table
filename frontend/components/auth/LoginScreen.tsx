"use client";

import { ArrowRight, CalendarDays, CheckCircle2, Loader2, Target } from "lucide-react";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ThemeToggle } from "@/components/app/ThemeToggle";
import { useAuthSessionState } from "@/features/auth/useAuthSessionState";
import { useHydrated } from "@/shared/lib/useHydrated";

interface LoginScreenProps {
  nextPath?: string;
}

const valuePoints = [
  {
    title: "오늘 일정 우선 확인",
    description: "지금 진행 중인 블록과 다음 일정을 먼저 보여줘서 바로 움직일 수 있습니다.",
    icon: CalendarDays,
  },
  {
    title: "목표와 시간을 연결",
    description: "목표 보드와 주간 시간표를 같은 화면 흐름으로 다뤄서 실행력을 유지합니다.",
    icon: Target,
  },
  {
    title: "설정은 단순하게 유지",
    description: "연동 상태와 엔진 파라미터를 한곳에서 조정하고 바로 저장 결과를 확인합니다.",
    icon: CheckCircle2,
  },
];

export const LoginScreen = ({ nextPath = "/dashboard" }: LoginScreenProps) => {
  const auth = useAuthSessionState();
  const router = useRouter();
  const hydrated = useHydrated();
  const [isStarting, setIsStarting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    if (!auth.isLoading && auth.session?.authenticated) {
      router.replace(nextPath);
    }
  }, [auth.isLoading, auth.session, nextPath, router]);

  if (!hydrated) {
    return null;
  }

  return (
    <main className="page-shell min-h-screen py-3 sm:py-5">
      <div className="flex min-h-[calc(100vh-1.5rem)] flex-col gap-4 sm:min-h-[calc(100vh-2.5rem)]">
        <header className="surface-card flex items-center justify-between p-4 sm:p-5">
          <div className="flex items-center gap-3">
            <div className="h-7 w-7 rounded-lg bg-[var(--foreground)]" />
            <div>
              <p className="text-sm font-extrabold tracking-tight uppercase">Time Table</p>
              <p className="text-xs text-[var(--foreground-muted)]">집중 일정 관리</p>
            </div>
          </div>
          <ThemeToggle />
        </header>

        <div className="flex flex-1 items-start pt-4 sm:pt-8 lg:pt-12">
          <section className="surface-card mx-auto w-full max-w-6xl p-6 sm:p-8 lg:p-10">
            <div className="grid gap-8 lg:grid-cols-[minmax(0,1.08fr)_minmax(320px,0.92fr)] lg:items-center">
              <div className="space-y-6">
                <div className="space-y-3">
                  <p className="metric-label">로그인</p>
                  <h1 className="max-w-3xl text-4xl font-extrabold tracking-tight text-[var(--foreground)] sm:text-[3.4rem] sm:leading-[1.02]">
                    오늘 일정부터 차분하게 정리하세요
                  </h1>
                  <p className="max-w-2xl text-base leading-7 text-[var(--foreground-muted)] sm:text-lg">
                    Google 계정으로 로그인하면 오늘 일정, 주간 시간표, 목표, 설정을 한 흐름으로 이어서 관리할 수 있습니다.
                  </p>
                </div>

                <div className="grid gap-3 sm:grid-cols-3">
                  <article className="surface-card-muted p-4">
                    <p className="metric-label">첫 화면</p>
                    <p className="mt-2 text-sm font-semibold text-[var(--foreground)]">오늘 일정과 다음 블록</p>
                  </article>
                  <article className="surface-card-muted p-4">
                    <p className="metric-label">주간 흐름</p>
                    <p className="mt-2 text-sm font-semibold text-[var(--foreground)]">요일별 시간표 즉시 편집</p>
                  </article>
                  <article className="surface-card-muted p-4">
                    <p className="metric-label">운영 제어</p>
                    <p className="mt-2 text-sm font-semibold text-[var(--foreground)]">목표와 엔진 설정 한곳 정리</p>
                  </article>
                </div>

                <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center">
                  <button
                    type="button"
                    onClick={async () => {
                      setIsStarting(true);
                      setActionError(null);
                      try {
                        await auth.startGoogleLogin();
                      } catch (error) {
                        console.error("Failed to start Google login", error);
                        setActionError("인증 세션을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.");
                      } finally {
                        setIsStarting(false);
                      }
                    }}
                    disabled={auth.isLoading || isStarting || auth.googleStart?.enabled === false}
                    className="btn-primary min-w-[220px]"
                  >
                    {isStarting ? (
                      <>
                        <Loader2 className="h-4 w-4 animate-spin" />
                        로그인 준비 중
                      </>
                    ) : (
                      <>
                        Google로 로그인
                        <ArrowRight className="h-4 w-4" />
                      </>
                    )}
                  </button>

                  <span className="text-sm text-[var(--foreground-muted)]">
                    로그인 후 바로 대시보드로 이동합니다.
                  </span>
                </div>

                {(actionError || auth.error || auth.googleStart?.message) && (
                  <div className="surface-card-muted p-4">
                    <p className="text-sm font-semibold text-[var(--foreground)]">
                      {actionError ?? auth.error ?? auth.googleStart?.message}
                    </p>
                  </div>
                )}
              </div>

              <div className="grid gap-4">
                {valuePoints.map((point) => {
                  const Icon = point.icon;

                  return (
                    <article key={point.title} className="surface-card-muted p-5">
                      <div className="flex items-start gap-4">
                        <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-[rgba(245,92,18,0.1)] text-[var(--accent)]">
                          <Icon className="h-5 w-5" />
                        </div>
                        <div className="space-y-2">
                          <h2 className="text-base font-bold text-[var(--foreground)]">{point.title}</h2>
                          <p className="text-sm leading-6 text-[var(--foreground-muted)]">{point.description}</p>
                        </div>
                      </div>
                    </article>
                  );
                })}
              </div>
            </div>
          </section>
        </div>
      </div>
    </main>
  );
};
