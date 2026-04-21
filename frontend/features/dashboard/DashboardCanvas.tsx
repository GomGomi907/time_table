"use client";

import { ArrowRight, CalendarRange, RefreshCw, Target } from "lucide-react";
import Link from "next/link";
import { useMemo, useState, useEffect } from "react";
import { TasksWidget } from "@/components/widgets/TasksWidget";
import { useGoals } from "@/features/goals/useGoals";
import { useWeekSchedule } from "@/features/schedule/useWeekSchedule";
import {
  CATEGORY_LABELS,
  DAY_FULL_LABELS,
  findDaySchedule,
  formatTimeRange,
  getCurrentBlock,
  getNextBlock,
  getProgressPercent,
  getRemainingLabel,
  getTodayDayKey,
  sortBlocks,
} from "@/features/schedule/utils";
import type { AuthSessionResponse, GoalResponse } from "@/shared/api/types";

interface DashboardCanvasProps {
  session: AuthSessionResponse;
}

const GOAL_CATEGORY_LABELS: Record<GoalResponse["category"], string> = {
  HEALTH: "건강",
  CAREER: "커리어",
  FINANCE: "재정",
  GROWTH: "성장",
  HOBBY: "취미",
  OTHER: "기타",
};

export const DashboardCanvas = ({ session }: DashboardCanvasProps) => {
  const schedule = useWeekSchedule();
  const goals = useGoals();
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30000);
    return () => window.clearInterval(timer);
  }, []);

  const todayKey = getTodayDayKey(now);
  const todayBlocks = useMemo(() => {
    if (!schedule.data) {
      return [];
    }

    return sortBlocks(findDaySchedule(schedule.data, todayKey).blocks);
  }, [schedule.data, todayKey]);

  const currentBlock = getCurrentBlock(todayBlocks, now);
  const nextBlock = getNextBlock(todayBlocks, now);
  const activeGoals = useMemo(
    () => goals.data.filter((goal) => goal.status !== "COMPLETED").slice(0, 4),
    [goals.data]
  );
  const progress = currentBlock ? getProgressPercent(currentBlock, now) : 0;

  return (
    <div className="space-y-6">
      <section className="surface-card p-6 sm:p-8">
        <div className="flex flex-col gap-6 xl:flex-row xl:items-start xl:justify-between">
          <div className="max-w-3xl space-y-3">
            <p className="metric-label">대시보드</p>
            <h1 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)] sm:text-[3.2rem]">
              오늘의 집중
            </h1>
            <p className="max-w-2xl text-base leading-7 text-[var(--foreground-muted)]">
              지금 진행 중인 일정, 다음 블록, 오늘 처리해야 할 목표와 할 일을 한 번에 확인합니다.
            </p>
          </div>

          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              onClick={() => {
                void schedule.refresh();
                void goals.refresh();
              }}
              className="btn-secondary"
            >
              <RefreshCw className={`h-4 w-4 ${schedule.isLoading || goals.isLoading ? "animate-spin" : ""}`} />
              새로고침
            </button>
            <Link href="/schedule" className="btn-secondary">
              <CalendarRange className="h-4 w-4" />
              시간표 보기
            </Link>
            <Link href="/goals" className="btn-primary">
              <Target className="h-4 w-4" />
              목표 보기
            </Link>
          </div>
        </div>
      </section>

      <div className="grid gap-6 xl:grid-cols-3">
        <section className="surface-card p-6 sm:p-8">
          <div className="flex flex-wrap items-center gap-2">
            <span className="status-badge">{DAY_FULL_LABELS[todayKey]}</span>
            <span className="status-badge">
              {session.googleConnectionStatus === "CONNECTED" ? "Google 연결됨" : "Google 미연결"}
            </span>
          </div>

          {currentBlock ? (
            <div className="mt-5 space-y-4">
              <div className="space-y-3">
                <p className="metric-label">현재 일정</p>
                <h2 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">
                  {currentBlock.activity}
                </h2>
                <p className="text-base leading-7 text-[var(--foreground-muted)]">
                  {formatTimeRange(currentBlock.startTime, currentBlock.endTime)}
                </p>
                {currentBlock.note && (
                  <p className="text-sm leading-6 text-[var(--foreground-muted)]">{currentBlock.note}</p>
                )}
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between text-sm">
                  <span className="font-semibold text-[var(--foreground)]">{getRemainingLabel(currentBlock, now)}</span>
                  <span className="text-[var(--accent)]">{Math.round(progress)}%</span>
                </div>
                <div className="h-2 rounded-full bg-[var(--surface-200)]">
                  <div
                    className="h-full rounded-full bg-[var(--accent)] transition-all duration-500"
                    style={{ width: `${progress}%` }}
                  />
                </div>
              </div>
            </div>
          ) : (
            <div className="mt-5 space-y-3">
              <p className="metric-label">현재 일정</p>
              <h2 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">오늘은 여유가 있습니다</h2>
              <p className="text-base leading-7 text-[var(--foreground-muted)]">
                진행 중인 블록이 없습니다. 다음 일정이나 목표를 먼저 정리해 두면 좋습니다.
              </p>
            </div>
          )}
        </section>

        <section className="surface-card p-6 sm:p-8">
          <p className="metric-label">다음 일정</p>
          {nextBlock ? (
            <div className="mt-5 space-y-4">
              <h2 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">{nextBlock.activity}</h2>
              <p className="text-base leading-7 text-[var(--foreground-muted)]">
                {formatTimeRange(nextBlock.startTime, nextBlock.endTime)}
              </p>
              <span className="status-badge">{CATEGORY_LABELS[nextBlock.category]}</span>
              <Link href="/schedule" className="btn-ghost mt-6 justify-between border border-[var(--border)]">
                시간표로 이동
                <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          ) : (
            <div className="mt-5 space-y-4">
              <h2 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">남은 일정이 없습니다</h2>
              <p className="text-base leading-7 text-[var(--foreground-muted)]">
                오늘 계획한 블록이 모두 끝났습니다. 필요하면 시간표에서 다음 블록을 추가하세요.
              </p>
              <Link href="/schedule" className="btn-ghost mt-6 justify-between border border-[var(--border)]">
                시간표로 이동
                <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          )}
        </section>

        <TasksWidget />
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_380px]">
        <section className="surface-card p-6 sm:p-8">
          <div className="flex items-center justify-between gap-3">
            <div>
              <p className="metric-label">오늘 일정</p>
              <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
                시간 순서로 보기
              </h2>
            </div>
            <span className="status-badge">{todayBlocks.length}개 블록</span>
          </div>

          <div className="mt-6 space-y-3">
            {todayBlocks.length > 0 ? (
              todayBlocks.map((block) => {
                const isCurrent = currentBlock?.id === block.id;
                const isNext = nextBlock?.id === block.id;

                return (
                  <article
                    key={block.id}
                    className={`rounded-2xl border p-4 transition-colors ${
                      isCurrent
                        ? "border-[rgba(245,92,18,0.28)] bg-[rgba(245,92,18,0.08)]"
                        : "border-[var(--border)] bg-[var(--surface-raised)]"
                    }`}
                  >
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                      <div className="space-y-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="status-badge">{formatTimeRange(block.startTime, block.endTime)}</span>
                          <span className="status-badge">{CATEGORY_LABELS[block.category]}</span>
                          {isCurrent && <span className="status-badge">진행 중</span>}
                          {!isCurrent && isNext && <span className="status-badge">다음 일정</span>}
                        </div>
                        <h3 className="text-lg font-bold text-[var(--foreground)]">{block.activity}</h3>
                        {block.note && (
                          <p className="text-sm leading-6 text-[var(--foreground-muted)]">{block.note}</p>
                        )}
                      </div>
                    </div>
                  </article>
                );
              })
            ) : (
              <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
                오늘 등록된 일정이 없습니다. 시간표에서 블록을 추가하면 이곳에 시간 순서대로 정리됩니다.
              </div>
            )}
          </div>
        </section>

        <section className="surface-card p-6 sm:p-8">
          <div className="flex items-center justify-between gap-3">
            <div>
              <p className="metric-label">핵심 목표</p>
              <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
                지금 챙길 목표
              </h2>
            </div>
            <Link href="/goals" className="btn-ghost">
              전체 보기
            </Link>
          </div>

          <div className="mt-6 space-y-3">
            {activeGoals.length > 0 ? (
              activeGoals.map((goal) => (
                <article key={goal.id} className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4">
                  <div className="flex items-center justify-between gap-3">
                    <span className="status-badge">{GOAL_CATEGORY_LABELS[goal.category]}</span>
                    <span className="text-sm font-semibold text-[var(--accent)]">{goal.progress}%</span>
                  </div>
                  <h3 className="mt-3 text-base font-bold text-[var(--foreground)]">{goal.title}</h3>
                  {goal.description && (
                    <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">{goal.description}</p>
                  )}
                  <div className="mt-4 h-2 rounded-full bg-[var(--surface-200)]">
                    <div
                      className="h-full rounded-full bg-[var(--accent)] transition-all duration-500"
                      style={{ width: `${goal.progress}%` }}
                    />
                  </div>
                </article>
              ))
            ) : (
              <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
                진행 중인 목표가 없습니다. 목표 보드에서 새 목표를 추가해 주세요.
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  );
};
