"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ReactNode, useEffect, useTransition } from "react";

import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useOnboardingBootstrap } from "@/hooks/use-onboarding-bootstrap";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { useAppStore } from "@/stores/app-store";

const NAV_ITEMS = [
  { href: "/dashboard", label: "대시보드" },
  { href: "/schedule", label: "주간 플래너" },
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
    onboardingStatus,
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
    ? { href: "/schedule", label: "주간 플래너" }
    : { href: "/dashboard", label: "대시보드" };

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
          <h1>세션과 작업공간을 준비하고 있습니다.</h1>
          <p>백엔드 세션 확인과 초기 쿠키 설정을 함께 진행 중입니다.</p>
        </div>
      </div>
    );
  }

  if (sessionPhase === "error") {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">연결 상태</p>
          <h1>세션을 확인하지 못했습니다.</h1>
          <p>{sessionError ?? "백엔드와 연결을 다시 확인해 주세요."}</p>
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
          <h1>로그인 후 작업공간이 열립니다.</h1>
          <p>
            백엔드는 로컬 사용자 초안을 준비하지만 보호된 API는 인증 이후에만 사용할 수 있습니다.
          </p>
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
          <p>로그인 직후 필요한 연결과 기본 옵션이 준비됐는지 확인하는 중입니다.</p>
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
          <p>{onboardingError ?? "온보딩 상태를 다시 불러와 주세요."}</p>
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
            <p className="brand-sub">실행 워크스페이스</p>
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

        <div className="sync-panel">
          <p className="panel-kicker">워크스페이스</p>
          <strong>{session.displayName}</strong>
          <span>{session.email}</span>
          <strong className="sync-inline-label">
            {onboardingStatus?.googleConnected || session.googleConnectionStatus === "CONNECTED"
              ? "Google 작업공간 연결됨"
              : "Google 연결 확인 중"}
          </strong>
          <span>마지막 기록 {formatDateTime(session.lastSyncAt, session.timezone)}</span>
        </div>

        <button
          className="ghost-btn wide-btn"
          disabled={isPending}
          onClick={() => startTransition(() => void handleLogout())}
        >
          {isPending ? "세션 종료 중..." : "로그아웃"}
        </button>
      </aside>

      <main className="content-shell">
        <header className="top-bar">
          <div className="top-bar-copy">
            <p className="eyebrow">{eyebrow}</p>
            <h1 className="sr-only">{title}</h1>
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
              집중 모드
            </Link>
          </div>
        </header>

        <div className="page-stack">{children}</div>
      </main>
    </div>
  );
}
