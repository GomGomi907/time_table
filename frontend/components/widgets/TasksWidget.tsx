"use client";

import { CheckCircle2, Circle, Loader2, RefreshCw } from "lucide-react";
import {
  formatTaskDueLabel,
  useTasksResource,
  type TaskResourceItem,
} from "./useTasksResource";

interface TasksWidgetProps {
  className?: string;
  maxItems?: number;
  resource?: {
    data: TaskResourceItem[];
    summary: {
      pending: TaskResourceItem[];
      completed: TaskResourceItem[];
      unassigned: TaskResourceItem[];
      dueSoon: TaskResourceItem[];
    };
    isLoading: boolean;
    error: string | null;
    refresh: () => Promise<void>;
  };
}

export const TasksWidget = ({
  className = "",
  maxItems = 5,
  resource,
}: TasksWidgetProps) => {
  const internalTasks = useTasksResource();
  const tasks = resource ?? internalTasks;
  const visiblePending = tasks.summary.pending.slice(0, maxItems);

  return (
    <section className={`surface-card h-full p-6 sm:p-8 ${className}`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="metric-label">할 일 영역</p>
          <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
            할 일 점검
          </h2>
          <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
            오늘 처리할 일, 미배치 일, 마감 임박 항목을 한 번에 훑어봅니다.
          </p>
        </div>

        <button
          type="button"
          onClick={() => void tasks.refresh()}
          className="icon-button"
          disabled={tasks.isLoading}
          aria-label="할 일 새로고침"
        >
          {tasks.isLoading ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <RefreshCw className="h-4 w-4" />
          )}
        </button>
      </div>

      <div className="mt-5 grid gap-3 sm:grid-cols-3">
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4">
          <p className="metric-label">열린 할 일</p>
          <p className="mt-2 text-2xl font-extrabold tracking-tight text-[var(--foreground)]">
            {tasks.summary.pending.length}
          </p>
        </div>
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4">
          <p className="metric-label">미배치</p>
          <p className="mt-2 text-2xl font-extrabold tracking-tight text-[var(--foreground)]">
            {tasks.summary.unassigned.length}
          </p>
        </div>
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4">
          <p className="metric-label">마감 임박</p>
          <p className="mt-2 text-2xl font-extrabold tracking-tight text-[var(--foreground)]">
            {tasks.summary.dueSoon.length}
          </p>
        </div>
      </div>

      <div className="mt-6 space-y-3">
        {tasks.isLoading && tasks.data.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
            할 일 목록을 불러오는 중입니다.
          </div>
        ) : tasks.error ? (
          <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6">
            <p className="text-sm font-semibold text-[var(--foreground)]">
              {tasks.error}
            </p>
            <p className="mt-2 text-sm text-[var(--foreground-muted)]">
              네트워크 상태나 Google 연동 상태를 확인해 주세요.
            </p>
          </div>
        ) : visiblePending.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
            대기 중인 할 일이 없습니다.
          </div>
        ) : (
          visiblePending.map((task) => (
            <article
              key={task.id}
              className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4"
            >
              <div className="flex items-start gap-3">
                <Circle className="mt-1 h-4 w-4 shrink-0 text-[var(--foreground-soft)]" />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-sm font-semibold text-[var(--foreground)]">
                      {task.title}
                    </p>
                    {!task.eventId && !task.scheduledStartAt ? (
                      <span className="status-badge">미배치</span>
                    ) : (
                      <span className="status-badge">배치됨</span>
                    )}
                  </div>
                  {task.notes ? (
                    <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
                      {task.notes}
                    </p>
                  ) : null}
                  <div className="mt-3 flex flex-wrap gap-2">
                    <span className="status-badge">{formatTaskDueLabel(task)}</span>
                    {task.estimatedMinutes ? (
                      <span className="status-badge">
                        예상 {task.estimatedMinutes}분
                      </span>
                    ) : null}
                  </div>
                </div>
              </div>
            </article>
          ))
        )}
      </div>

      {tasks.summary.completed.length > 0 ? (
        <div className="mt-5 rounded-2xl border border-[var(--border)] bg-[var(--surface-muted)] p-4">
          <div className="flex items-center gap-2 text-sm font-semibold text-[var(--foreground)]">
            <CheckCircle2 className="h-4 w-4 text-[var(--success)]" />
            완료된 항목 {tasks.summary.completed.length}개
          </div>
          <p className="mt-2 text-sm text-[var(--foreground-muted)]">
            최근 완료된 흐름이 유지되고 있습니다.
          </p>
        </div>
      ) : null}
    </section>
  );
};
