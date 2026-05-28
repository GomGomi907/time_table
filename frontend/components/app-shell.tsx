"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ReactNode, useEffect, useTransition } from "react";

import { api } from "@/lib/api";
import { useOnboardingBootstrap } from "@/hooks/use-onboarding-bootstrap";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { useAppStore } from "@/stores/app-store";

const NAV_ITEMS = [
  { href: "/dashboard", label: "오늘" },
  { href: "/schedule", label: "주간 일정" },
  { href: "/focus", label: "실행 모드" },
];

interface AppShellProps {
  eyebrow: string;
  title: string;
  description?: string;
  actions?: ReactNode;
  children: ReactNode;
  immersive?: boolean;
}

export function AppShell({
  eyebrow,
  title,
  description,
  actions,
  children,
  immersive = false,
}: AppShellProps) {
  const pathname = usePathname();
  const router = useRouter();
  const { session, sessionPhase, sessionError, refreshSession } = useSessionBootstrap();
  const {
    onboardingPhase,
    onboardingError,
    onboardingCompleted,
    needsOnboarding,
    refreshOnboarding,
  } = useOnboardingBootstrap();
  const clearSession = useAppStore((state) => state.clearSession);
  const showNotice = useAppStore((state) => state.showNotice);
  const [isPending, startTransition] = useTransition();
  const isDashboardPage = pathname === "/dashboard";
  const isFocusPage = pathname === "/focus";

  const primaryShortcut = isDashboardPage
    ? { href: "/schedule", label: "주간 일정" }
    : { href: "/dashboard", label: "오늘" };
  async function handleLogout() {
    try {
      await api.logout();
      clearSession();
      showNotice({
        tone: "info",
        title: "세션을 종료했습니다.",
        detail: "다시 시작하려면 로그인 화면으로 이동해 주세요.",
      });
      router.push("/login");
    } catch (error) {
      showNotice({
        tone: "error",
        title: "로그아웃에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  useEffect(() => {
    if (needsOnboarding && pathname !== "/onboarding") {
      router.replace("/onboarding");
    }
  }, [needsOnboarding, pathname, router]);

  if (sessionPhase === "loading" || sessionPhase === "idle") {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">불러오는 중</p>
          <h1>세션과 작업 공간을 준비하고 있습니다.</h1>
          <p>로그인 상태와 기본 설정을 확인하고 있습니다.</p>
        </div>
      </div>
    );
  }

  if (sessionPhase === "error") {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">오류</p>
          <h1>세션을 확인하지 못했습니다.</h1>
          <p>{sessionError ?? "잠시 후 다시 시도해 주세요."}</p>
          <button className="solid-btn" onClick={() => void refreshSession()}>
            다시 시도
          </button>
        </div>
      </div>
    );
  }

  if (!session?.authenticated) {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">접근 안내</p>
          <h1>로그인 후 작업 공간이 열립니다.</h1>
          <p>오늘 일정과 지금 할 일을 보려면 로그인이 필요합니다.</p>
          <div className="guest-actions">
            <Link className="solid-btn link-btn" href="/login">
              로그인 화면으로 이동
            </Link>
            <button className="ghost-btn" onClick={() => void refreshSession()}>
              세션 다시 확인
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!onboardingCompleted && (onboardingPhase === "idle" || onboardingPhase === "loading")) {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">초기 설정 확인</p>
          <h1>첫 사용 설정 상태를 확인하고 있습니다.</h1>
          <p>처음 설정을 준비하고 있습니다.</p>
        </div>
      </div>
    );
  }

  if (!onboardingCompleted && onboardingPhase === "error") {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">초기 설정 확인</p>
          <h1>초기 설정 상태를 확인하지 못했습니다.</h1>
          <p>{onboardingError ?? "처음 설정 상태를 다시 불러와 주세요."}</p>
          <button className="solid-btn" onClick={() => void refreshOnboarding()}>
            다시 시도
          </button>
        </div>
      </div>
    );
  }

  if (needsOnboarding && pathname !== "/onboarding") {
    return null;
  }

  if (immersive) {
    return (
      <div className="immersive-shell">
        <main className="immersive-main">{children}</main>
      </div>
    );
  }

  return (
    <div className="app-frame">
      <aside className="side-nav">
        <div className="brand-block">
          <span className="brand-mark">TT</span>
          <div>
            <p className="brand-name">Time Table</p>
            <p className="brand-sub">일정 운영판</p>
          </div>
        </div>

        <nav className="nav-links" aria-label="주요 화면">
          {NAV_ITEMS.map((item) => {
            const isActive = pathname === item.href;
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`nav-link ${isActive ? "active" : ""}`}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>


        <button
          className="ghost-btn wide-btn"
          disabled={isPending}
          onClick={() => startTransition(() => void handleLogout())}
        >
          {isPending ? "세션 종료 중..." : "로그아웃"}
        </button>
      </aside>

      <main className="content-shell">
        <header className={`top-bar ${isDashboardPage ? "dashboard-top-bar" : ""}`}>
          <div className="top-bar-copy">
            <p className="eyebrow">{eyebrow}</p>
            <h1 className="top-title">{title}</h1>
            {description ? <p className="hero-copy compact">{description}</p> : null}
          </div>
          <div className="top-actions">
            <Link className="ghost-btn link-btn" href={primaryShortcut.href}>
              {primaryShortcut.label}
            </Link>
            {actions}
            <Link
              className={`solid-btn link-btn top-focus-entry ${isFocusPage ? "current" : ""}`}
              href="/focus"
              aria-current={isFocusPage ? "page" : undefined}
            >
              실행 모드
            </Link>
          </div>
        </header>

        <div className="page-stack">{children}</div>
      </main>
    </div>
  );
}
