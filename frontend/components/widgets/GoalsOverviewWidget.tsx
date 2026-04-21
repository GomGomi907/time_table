"use client";

import Link from "next/link";
import type { GoalResponse } from "@/shared/api/types";

const GOAL_CATEGORY_LABELS: Record<GoalResponse["category"], string> = {
  HEALTH: "건강",
  CAREER: "커리어",
  FINANCE: "재정",
  GROWTH: "성장",
  HOBBY: "취미",
  OTHER: "기타",
};

interface GoalsOverviewWidgetProps {
  goals: GoalResponse[];
}

export const GoalsOverviewWidget = ({ goals }: GoalsOverviewWidgetProps) => {
  const activeGoals = goals.filter((goal) => goal.status !== "COMPLETED").slice(0, 4);

  return (
    <section className="surface-card p-6 sm:p-8">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="metric-label">목표 영역</p>
          <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
            목표 진행
          </h2>
          <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
            이번 주 목표의 진척도와 막힌 지점을 빠르게 확인합니다.
          </p>
        </div>

        <Link href="/goals" className="btn-ghost">
          전체 보기
        </Link>
      </div>

      <div className="mt-6 space-y-3">
        {activeGoals.length > 0 ? (
          activeGoals.map((goal) => (
            <article
              key={goal.id}
              className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4"
            >
              <div className="flex items-center justify-between gap-3">
                <span className="status-badge">
                  {GOAL_CATEGORY_LABELS[goal.category]}
                </span>
                <span className="text-sm font-semibold text-[var(--accent)]">
                  {goal.progress}%
                </span>
              </div>
              <h3 className="mt-3 text-base font-bold text-[var(--foreground)]">
                {goal.title}
              </h3>
              {goal.description ? (
                <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
                  {goal.description}
                </p>
              ) : null}
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
  );
};
