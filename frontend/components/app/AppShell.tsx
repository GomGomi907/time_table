"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  CalendarRange,
  Crosshair,
  LayoutDashboard,
  LogOut,
  Settings2,
  Target,
} from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import type { AuthSessionResponse } from "@/shared/api/types";
import { ThemeToggle } from "./ThemeToggle";

interface AppShellProps {
  session: AuthSessionResponse;
  onLogout: () => Promise<void>;
  children: ReactNode;
}

const navItems = [
  { href: "/dashboard", label: "워크스페이스", icon: LayoutDashboard },
  { href: "/focus", label: "집중", icon: Crosshair },
  { href: "/schedule", label: "시간표", icon: CalendarRange },
  { href: "/goals", label: "목표", icon: Target },
  { href: "/settings", label: "설정", icon: Settings2 },
];

const formatNowLabel = (date: Date) =>
  new Intl.DateTimeFormat("ko-KR", {
    month: "long",
    day: "numeric",
    weekday: "long",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);

export const AppShell = ({ session, onLogout, children }: AppShellProps) => {
  const pathname = usePathname();
  const [nowLabel, setNowLabel] = useState(() => formatNowLabel(new Date()));

  useEffect(() => {
    const timer = window.setInterval(() => {
      setNowLabel(formatNowLabel(new Date()));
    }, 30000);

    return () => window.clearInterval(timer);
  }, []);

  const isConnected = session.googleConnectionStatus === "CONNECTED";

  return (
    <main className="page-shell py-3 sm:py-5">
      <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[220px_minmax(0,1fr)] lg:items-start lg:gap-6">
        <aside className="surface-card hidden p-5 lg:flex lg:min-h-[calc(100vh-2.5rem)] lg:flex-col lg:sticky lg:top-5">
          <div className="flex items-center gap-3 px-1 py-1">
            <div className="h-7 w-7 rounded-lg bg-[var(--foreground)]" />
            <div>
              <p className="text-sm font-extrabold tracking-tight uppercase">Time Table</p>
              <p className="text-xs text-[var(--foreground-muted)]">집중 일정 도구</p>
            </div>
          </div>

          <nav className="mt-8 grid gap-2">
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive = pathname === item.href || pathname.startsWith(`${item.href}/`);

              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`nav-link ${isActive ? "nav-link-active" : ""}`}
                >
                  <Icon className="h-4 w-4" />
                  <span className="text-sm font-semibold">{item.label}</span>
                </Link>
              );
            })}
          </nav>

          <div className="mt-auto space-y-4">
            <div className="surface-card-muted p-4">
              <div className="flex items-center gap-2">
                <span className={`h-2.5 w-2.5 rounded-full ${isConnected ? "bg-[var(--success)]" : "bg-[var(--error)]"}`} />
                <span className="text-sm font-semibold">{isConnected ? "Google 연결됨" : "Google 미연결"}</span>
              </div>
              <p className="mt-2 truncate text-sm text-[var(--foreground-muted)]">
                {session.email ?? session.displayName ?? "로그인이 필요합니다"}
              </p>
            </div>

            <button type="button" onClick={() => void onLogout()} className="nav-link">
              <LogOut className="h-4 w-4" />
              <span className="text-sm font-semibold">로그아웃</span>
            </button>
          </div>
        </aside>

        <div className="space-y-4">
          <div className="surface-card p-4 lg:hidden">
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-3">
                <div className="h-7 w-7 rounded-lg bg-[var(--foreground)]" />
                <div>
                  <p className="text-sm font-extrabold tracking-tight uppercase">Time Table</p>
                  <p className="text-xs text-[var(--foreground-muted)]">{nowLabel}</p>
                </div>
              </div>
              <ThemeToggle />
            </div>

            <div className="mt-3 flex items-center gap-2">
              <span className={`h-2.5 w-2.5 rounded-full ${isConnected ? "bg-[var(--success)]" : "bg-[var(--error)]"}`} />
              <span className="text-sm font-semibold">{isConnected ? "Google 연결됨" : "Google 미연결"}</span>
              <span className="min-w-0 truncate text-sm text-[var(--foreground-muted)]">
                {session.email ?? session.displayName ?? "로그인이 필요합니다"}
              </span>
            </div>

            <nav className="mt-4 flex gap-2 overflow-x-auto pb-1">
              {navItems.map((item) => {
                const Icon = item.icon;
                const isActive = pathname === item.href || pathname.startsWith(`${item.href}/`);

                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={`nav-link w-auto min-w-[82px] shrink-0 flex-col justify-center gap-1 px-3 py-3 ${isActive ? "nav-link-active" : ""}`}
                  >
                    <Icon className="h-4 w-4" />
                    <span className="text-xs font-semibold">{item.label}</span>
                  </Link>
                );
              })}
            </nav>

            <button type="button" onClick={() => void onLogout()} className="btn-ghost mt-2 w-full justify-center">
              <LogOut className="h-4 w-4" />
              로그아웃
            </button>
          </div>

          <header className="surface-card hidden gap-3 p-5 lg:flex lg:flex-row lg:items-center lg:justify-between">
            <div>
              <p className="metric-label">현재 시간</p>
              <p className="mt-1 text-sm font-semibold text-[var(--foreground)]">{nowLabel}</p>
            </div>

            <div className="flex items-center gap-3">
              <span className="status-badge">
                <span className={`h-2 w-2 rounded-full ${isConnected ? "bg-[var(--success)]" : "bg-[var(--error)]"}`} />
                {isConnected ? "Google 연결됨" : "Google 미연결"}
              </span>
              <span className="text-sm text-[var(--foreground-muted)]">
                {session.email ?? session.displayName ?? "로그인이 필요합니다"}
              </span>
              <ThemeToggle />
            </div>
          </header>

          {children}
        </div>
      </div>
    </main>
  );
};
