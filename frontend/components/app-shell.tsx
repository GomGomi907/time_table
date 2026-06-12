"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ReactNode, useEffect, useState, useTransition } from "react";

import { api } from "@/lib/api";
import { BrandLogo } from "@/components/brand-logo";
import { ConfirmDialog } from "@/components/confirm-dialog";
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
  /** Page-owned secondary surface. Desktop renders it as a right rail; tablet/mobile stack it below content. */
  rightRail?: ReactNode;
  children: ReactNode;
  immersive?: boolean;
  showTopBar?: boolean;
  workspaceWidth?: "standard" | "wide";
}

export function AppShell({
  eyebrow,
  title,
  description,
  actions,
  rightRail,
  children,
  immersive = false,
  showTopBar = true,
  workspaceWidth = "standard",
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
  const [accountAction, setAccountAction] = useState<"disconnect" | "delete" | null>(null);
  const [confirmAccountAction, setConfirmAccountAction] = useState<"disconnect" | "delete" | null>(null);

  const googleConnected = ["CONNECTED", "SYNCING", "DEGRADED", "ERROR"].includes(
    session?.googleConnectionStatus ?? "",
  );

  async function handleLogout() {
    try {
      await api.logout();
      clearSession();
      showNotice({
        tone: "info",
        title: "세션을 종료했습니다.",
        detail: "로그인 화면에서 다시 시작할 수 있습니다.",
      });
      window.location.assign("/login");
    } catch (error) {
      showNotice({
        tone: "error",
        title: "로그아웃에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도하면 됩니다.",
      });
    }
  }

  async function handleDisconnectGoogle() {
    if (!googleConnected) {
      return;
    }

    setAccountAction("disconnect");
    try {
      await api.disconnectGoogle();
      await refreshSession();
      setConfirmAccountAction(null);
      showNotice({
        tone: "info",
        title: "Google 연결을 해제했습니다.",
        detail: "다시 연결하려면 로그아웃 후 Google로 다시 로그인해 동의하면 됩니다.",
      });
    } catch (error) {
      showNotice({
        tone: "error",
        title: "Google 연결 해제에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도하면 됩니다.",
      });
    } finally {
      setAccountAction(null);
    }
  }

  async function handleDeleteAccount() {
    setAccountAction("delete");
    try {
      await api.deleteAccount();
      clearSession();
      window.location.assign("/");
    } catch (error) {
      showNotice({
        tone: "error",
        title: "계정 삭제에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도하거나 지원 이메일로 문의해 주세요.",
      });
      setAccountAction(null);
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
          <h1>세션과 작업 공간을 준비합니다.</h1>
          <p>로그인 상태와 기본 설정을 확인합니다.</p>
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
          <p>{sessionError ?? "잠시 후 다시 시도하면 됩니다."}</p>
          <button className="solid-btn" data-testid="status-retry-action" onClick={() => void refreshSession()}>
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
          <p>로그인하면 오늘 일정과 지금 할 일이 열립니다.</p>
          <div className="guest-actions">
            <Link className="solid-btn link-btn" href="/login">
              로그인 화면으로 이동
            </Link>
            <button className="ghost-btn" data-testid="status-retry-action" onClick={() => void refreshSession()}>
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
          <h1>첫 사용 설정 상태를 확인합니다.</h1>
          <p>처음 설정을 준비합니다.</p>
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
          <p>{onboardingError ?? "처음 설정 상태를 다시 불러올 수 있습니다."}</p>
          <button className="solid-btn" data-testid="status-retry-action" onClick={() => void refreshOnboarding()}>
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
    <div
      className={`app-frame ${rightRail ? "with-right-rail" : ""} ${
        showTopBar ? "" : "no-top-bar"
      } ${workspaceWidth === "wide" ? "wide-workspace" : ""}`}
    >
      <aside className="side-nav">
        <div className="brand-block">
          <span className="brand-mark logo-mark">
            <BrandLogo />
          </span>
          <div>
            <p className="brand-name">Time Table</p>
            <p className="brand-sub">일정 운영판</p>
          </div>
        </div>

        <nav className="nav-links" aria-label="주요 화면" data-testid="shell-primary-nav">
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


        <section className="account-control-panel" aria-label="계정 및 Google 연결 관리">
          <p className="panel-kicker">Google 연결</p>
          <p className={`connection-status-pill ${googleConnected ? "connected" : "disconnected"}`}>
            {googleConnected ? "연결됨" : "연결 안 됨"}
          </p>
          <p className="micro-copy">
            저장된 Google 토큰을 삭제하거나 Time Table 계정 데이터를 삭제할 수 있습니다.
          </p>
          <button
            className="ghost-btn wide-btn compact-account-btn"
            disabled={!googleConnected || isPending || accountAction !== null}
            onClick={() => setConfirmAccountAction("disconnect")}
          >
            {accountAction === "disconnect" ? "해제 중..." : "Google 연결 해제"}
          </button>
          <button
            className="danger-btn wide-btn compact-account-btn"
            disabled={isPending || accountAction !== null}
            onClick={() => setConfirmAccountAction("delete")}
          >
            {accountAction === "delete" ? "삭제 중..." : "계정·데이터 삭제"}
          </button>
        </section>

        <button
          className="ghost-btn wide-btn"
          disabled={isPending || accountAction !== null}
          onClick={() => startTransition(() => void handleLogout())}
        >
          {isPending ? "세션 종료 중..." : "로그아웃"}
        </button>

        <nav className="side-legal-links" aria-label="서비스 고지">
          <Link href="/privacy">개인정보처리방침</Link>
          <Link href="/terms">서비스 약관</Link>
        </nav>
      </aside>

      <main className="content-shell">
        {showTopBar ? (
          <header className="top-bar">
            <div className="top-bar-copy">
              <p className="eyebrow">{eyebrow}</p>
              <h1 className="top-title">{title}</h1>
              {description ? <p className="hero-copy compact">{description}</p> : null}
            </div>
            {actions ? (
              <div className="top-actions">
                {actions}
              </div>
            ) : null}
          </header>
        ) : null}

        <div className="page-stack">{children}</div>
      </main>

      {rightRail ? (
        <aside className="app-right-rail" data-testid="app-right-rail" aria-label="보조 작업 패널">
          {rightRail}
        </aside>
      ) : null}

      {confirmAccountAction === "disconnect" ? (
        <ConfirmDialog
          title="Google 연결을 해제할까요?"
          description="저장된 Google OAuth 토큰과 권한 상태를 삭제하고 새 동기화·외부 반영을 중지합니다. 서비스 안에 이미 저장된 일정 데이터는 계정 삭제 전까지 유지됩니다."
          confirmLabel="연결 해제"
          isPending={accountAction === "disconnect"}
          onCancel={() => {
            if (accountAction === null) {
              setConfirmAccountAction(null);
            }
          }}
          onConfirm={() => void handleDisconnectGoogle()}
        />
      ) : null}

      {confirmAccountAction === "delete" ? (
        <ConfirmDialog
          title="계정과 데이터를 삭제할까요?"
          description="Time Table 계정, 서비스 내 일정·할 일·목표, 동기화 기록, AI 요청 기록을 삭제합니다. 이 작업은 되돌릴 수 없습니다."
          confirmLabel="계정·데이터 삭제"
          isPending={accountAction === "delete"}
          onCancel={() => {
            if (accountAction === null) {
              setConfirmAccountAction(null);
            }
          }}
          onConfirm={() => void handleDeleteAccount()}
        />
      ) : null}
    </div>
  );
}
