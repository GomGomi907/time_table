"use client";

import { Link2, RefreshCw } from "lucide-react";
import Link from "next/link";
import type { AuthSessionResponse, WeekScheduleResponse } from "@/shared/api/types";

interface SyncStatusWidgetProps {
  session: AuthSessionResponse;
  schedule: WeekScheduleResponse | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const formatLastSync = (value: string | null) => {
  if (!value) {
    return "아직 동기화 기록이 없습니다.";
  }

  const parsed = new Date(value);

  if (Number.isNaN(parsed.getTime())) {
    return "동기화 시각 확인 필요";
  }

  return new Intl.DateTimeFormat("ko-KR", {
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(parsed);
};

export const SyncStatusWidget = ({
  session,
  schedule,
  isRefreshing,
  onRefresh,
}: SyncStatusWidgetProps) => {
  const allBlocks = schedule?.week.flatMap((day) => day.blocks) ?? [];
  const importedCount = allBlocks.filter(
    (block) => block.sourceType !== "MANUAL"
  ).length;
  const localCount = allBlocks.filter((block) => block.sourceType === "MANUAL").length;
  const isConnected = session.googleConnectionStatus === "CONNECTED";

  return (
    <section className="surface-card p-6 sm:p-8">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="metric-label">동기화 상태</p>
          <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
            연동 상태
          </h2>
          <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
            Google 연동 상태와 마지막 반영 시점을 현재 화면에서 함께 확인합니다.
          </p>
        </div>
        <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-[rgba(245,92,18,0.1)] text-[var(--accent)]">
          <Link2 className="h-5 w-5" />
        </div>
      </div>

      <div className="mt-6 grid gap-3 sm:grid-cols-2">
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4">
          <p className="metric-label">Google 상태</p>
          <p className="mt-2 text-lg font-bold text-[var(--foreground)]">
            {isConnected ? "연결됨" : "연결 필요"}
          </p>
          <p className="mt-2 text-sm text-[var(--foreground-muted)]">
            Calendar / Tasks 연동 준비 상태를 기준으로 표시합니다.
          </p>
        </div>
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4">
          <p className="metric-label">마지막 동기화</p>
          <p className="mt-2 text-lg font-bold text-[var(--foreground)]">
            {formatLastSync(session.lastSyncAt)}
          </p>
          <p className="mt-2 text-sm text-[var(--foreground-muted)]">
            현재 세션 기준 최신 반영 시각입니다.
          </p>
        </div>
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-muted)] p-4">
          <p className="metric-label">가져온 일정</p>
          <p className="mt-2 text-2xl font-extrabold tracking-tight text-[var(--foreground)]">
            {importedCount}
          </p>
        </div>
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-muted)] p-4">
          <p className="metric-label">로컬 편집</p>
          <p className="mt-2 text-2xl font-extrabold tracking-tight text-[var(--foreground)]">
            {localCount}
          </p>
        </div>
      </div>

      <div className="mt-6 flex flex-wrap gap-3">
        <button type="button" onClick={onRefresh} className="btn-secondary">
          <RefreshCw className={`h-4 w-4 ${isRefreshing ? "animate-spin" : ""}`} />
          데이터 새로고침
        </button>
        <Link href="/settings" className="btn-ghost border border-[var(--border)]">
          연동 설정 열기
        </Link>
      </div>
    </section>
  );
};
