"use client";

import { CheckCircle2, Circle, Loader2, RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { apiClient } from "@/shared/api/client";

interface TaskItem {
  id: string;
  title: string;
  notes?: string;
  status: string;
  due?: string;
}

export const TasksWidget = () => {
  const [tasks, setTasks] = useState<TaskItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchTasks = async () => {
    setIsLoading(true);
    try {
      const { data } = await apiClient.get<TaskItem[]>("/tasks");
      setTasks(data);
      setError(null);
    } catch (err) {
      console.error("Failed to fetch tasks", err);
      setError("할 일을 불러오지 못했습니다.");
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    void fetchTasks();
  }, []);

  return (
    <section className="surface-card h-full p-6 sm:p-8">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="metric-label">주요 추진 일감</p>
          <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">할 일 점검</h2>
          <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
            Google Tasks에서 가져온 일을 우선순위 점검용으로 간단히 확인합니다.
          </p>
        </div>

        <button type="button" onClick={() => void fetchTasks()} className="icon-button" disabled={isLoading} aria-label="할 일 새로고침">
          {isLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
        </button>
      </div>

      <div className="mt-6 space-y-3">
        {isLoading && tasks.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
            할 일 목록을 불러오는 중입니다.
          </div>
        ) : error ? (
          <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6">
            <p className="text-sm font-semibold text-[var(--foreground)]">{error}</p>
            <p className="mt-2 text-sm text-[var(--foreground-muted)]">네트워크 상태나 Google 연동 상태를 확인해 주세요.</p>
          </div>
        ) : tasks.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
            대기 중인 할 일이 없습니다.
          </div>
        ) : (
          tasks.slice(0, 5).map((task) => (
            <article
              key={task.id}
              className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4"
            >
              <div className="flex items-start gap-3">
                {task.status === "completed" ? (
                  <CheckCircle2 className="mt-1 h-4 w-4 shrink-0 text-[var(--success)]" />
                ) : (
                  <Circle className="mt-1 h-4 w-4 shrink-0 text-[var(--foreground-soft)]" />
                )}
                <div className="min-w-0 flex-1">
                  <p
                    className={`text-sm font-semibold ${
                      task.status === "completed"
                        ? "text-[var(--foreground-muted)] line-through opacity-70"
                        : "text-[var(--foreground)]"
                    }`}
                  >
                    {task.title}
                  </p>
                  {task.notes && <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">{task.notes}</p>}
                </div>
              </div>
            </article>
          ))
        )}
      </div>

      {tasks.length > 5 && (
        <p className="mt-4 text-sm text-[var(--foreground-muted)]">외 {tasks.length - 5}개의 추가 할 일이 남아 있습니다.</p>
      )}
    </section>
  );
};
