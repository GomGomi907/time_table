"use client";

import { AlertTriangle, CheckCircle2, Loader2, Plus, RefreshCw, Target, Trash2 } from "lucide-react";
import { type ReactNode, useMemo, useState } from "react";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { GoalCategory, GoalCreateRequest, GoalResponse, GoalStatus } from "@/shared/api/types";
import { useGoals } from "./useGoals";

const GOAL_CATEGORY_LABELS: Record<GoalResponse["category"], string> = {
  HEALTH: "건강",
  CAREER: "커리어",
  FINANCE: "재정",
  GROWTH: "성장",
  HOBBY: "취미",
  OTHER: "기타",
};

const GOAL_STATUS_LABELS: Record<GoalResponse["status"], string> = {
  PENDING: "대기",
  IN_PROGRESS: "진행 중",
  COMPLETED: "완료",
  FAILED: "위험",
};

const columns: Array<{
  key: GoalStatus;
  label: string;
  description: string;
  icon: ReactNode;
}> = [
  {
    key: "PENDING",
    label: "대기",
    description: "곧 시작할 목표",
    icon: <Target className="h-4 w-4" />,
  },
  {
    key: "IN_PROGRESS",
    label: "진행",
    description: "지금 밀고 있는 목표",
    icon: <Target className="h-4 w-4" />,
  },
  {
    key: "COMPLETED",
    label: "완료",
    description: "이번 주에 끝낸 목표",
    icon: <CheckCircle2 className="h-4 w-4" />,
  },
  {
    key: "FAILED",
    label: "위험",
    description: "지연되거나 막힌 목표",
    icon: <AlertTriangle className="h-4 w-4" />,
  },
];

export const GoalsWorkspace = () => {
  const goals = useGoals();
  const [editingGoal, setEditingGoal] = useState<GoalResponse | null>(null);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [form, setForm] = useState<GoalCreateRequest>({
    title: "",
    description: "",
    category: "GROWTH",
    status: "PENDING",
    progress: 0,
  });

  const stats = useMemo(() => {
    const inProgress = goals.data.filter((goal) => goal.status === "IN_PROGRESS").length;
    const completed = goals.data.filter((goal) => goal.status === "COMPLETED").length;
    const failed = goals.data.filter((goal) => goal.status === "FAILED").length;
    const averageProgress =
      goals.data.length > 0
        ? Math.round(goals.data.reduce((sum, goal) => sum + goal.progress, 0) / goals.data.length)
        : 0;

    return { inProgress, completed, failed, averageProgress };
  }, [goals.data]);

  const openForCreate = () => {
    setEditingGoal(null);
    setForm({
      title: "",
      description: "",
      category: "GROWTH",
      status: "PENDING",
      progress: 0,
    });
    setFeedback(null);
    setIsDrawerOpen(true);
  };

  const openForEdit = (goal: GoalResponse) => {
    setEditingGoal(goal);
    setForm({
      title: goal.title,
      description: goal.description ?? "",
      category: goal.category,
      status: goal.status,
      progress: goal.progress,
    });
    setFeedback(null);
    setIsDrawerOpen(true);
  };

  const handleSubmit = async () => {
    if (!form.title?.trim()) {
      setFeedback("목표 제목을 입력해 주세요.");
      return;
    }

    try {
      if (editingGoal) {
        await goals.updateGoal(editingGoal.id, {
          ...form,
          title: form.title.trim(),
          description: form.description?.trim() ? form.description.trim() : null,
        });
        setFeedback("목표를 수정했습니다.");
      } else {
        await goals.createGoal({
          ...form,
          title: form.title.trim(),
          description: form.description?.trim() ? form.description.trim() : null,
        });
        setFeedback("새 목표를 추가했습니다.");
      }

      setIsDrawerOpen(false);
    } catch (error) {
      setFeedback(getApiErrorMessage(error, "목표를 저장하지 못했습니다."));
    }
  };

  const handleDelete = async () => {
    if (!editingGoal) {
      return;
    }

    try {
      await goals.deleteGoal(editingGoal.id);
      setFeedback("목표를 삭제했습니다.");
      setIsDrawerOpen(false);
    } catch (error) {
      setFeedback(getApiErrorMessage(error, "목표를 삭제하지 못했습니다."));
    }
  };

  return (
    <div className="space-y-6">
      <section className="surface-card p-6 sm:p-8">
        <div className="flex flex-col gap-6 xl:flex-row xl:items-start xl:justify-between">
          <div className="max-w-3xl space-y-3">
            <p className="metric-label">목표</p>
            <h1 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)] sm:text-4xl">
              이번 주 목표
            </h1>
            <p className="max-w-2xl text-base leading-7 text-[var(--foreground-muted)]">
              대기, 진행, 완료, 위험 상태로 나눠서 목표를 바로 정리합니다. 카드를 누르면 같은 드로어에서 생성과 수정이 모두 가능합니다.
            </p>
          </div>

          <div className="flex flex-wrap gap-3">
            <button type="button" onClick={() => void goals.refresh()} className="btn-secondary">
              <RefreshCw className={`h-4 w-4 ${goals.isLoading ? "animate-spin" : ""}`} />
              동기화
            </button>
            <button type="button" onClick={openForCreate} className="btn-primary">
              <Plus className="h-4 w-4" />
              목표 추가
            </button>
          </div>
        </div>
      </section>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <article className="metric-card">
          <p className="metric-label">전체 목표</p>
          <p className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">{goals.data.length}</p>
          <p className="text-sm text-[var(--foreground-muted)]">이번 주 관리 중인 목표 수</p>
        </article>
        <article className="metric-card">
          <p className="metric-label">진행 중</p>
          <p className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">{stats.inProgress}</p>
          <p className="text-sm text-[var(--foreground-muted)]">지금 추진 중인 목표</p>
        </article>
        <article className="metric-card">
          <p className="metric-label">완료 / 위험</p>
          <p className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">
            {stats.completed} / {stats.failed}
          </p>
          <p className="text-sm text-[var(--foreground-muted)]">완료한 목표와 지연된 목표</p>
        </article>
        <article className="metric-card">
          <p className="metric-label">평균 진척도</p>
          <p className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">{stats.averageProgress}%</p>
          <p className="text-sm text-[var(--foreground-muted)]">전체 목표 기준 평균 진행률</p>
        </article>
      </div>

      {(feedback || goals.error) && (
        <div className="surface-card-muted p-4">
          <p className="text-sm font-semibold text-[var(--foreground)]">{feedback ?? goals.error}</p>
        </div>
      )}

      <section className="grid gap-4 xl:grid-cols-4 md:grid-cols-2">
        {columns.map((column) => {
          const columnGoals = goals.data.filter((goal) => goal.status === column.key);

          return (
            <article key={column.key} className="surface-card p-0">
              <div className="border-b border-[var(--border)] px-5 py-4">
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <div className="flex h-9 w-9 items-center justify-center rounded-2xl bg-[rgba(245,92,18,0.1)] text-[var(--accent)]">
                      {column.icon}
                    </div>
                    <div>
                      <h2 className="text-lg font-bold text-[var(--foreground)]">{column.label}</h2>
                      <p className="text-sm text-[var(--foreground-muted)]">{column.description}</p>
                    </div>
                  </div>
                  <span className="status-badge">{columnGoals.length}개</span>
                </div>
              </div>

              <div className="space-y-3 px-5 py-5">
                {columnGoals.length > 0 ? (
                  columnGoals.map((goal) => (
                    <button
                      key={goal.id}
                      type="button"
                      onClick={() => openForEdit(goal)}
                      className="w-full rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4 text-left hover:border-[rgba(245,92,18,0.28)]"
                    >
                      <div className="flex items-center justify-between gap-3">
                        <span className="status-badge">{GOAL_CATEGORY_LABELS[goal.category]}</span>
                        <span className="text-sm font-semibold text-[var(--accent)]">{goal.progress}%</span>
                      </div>
                      <h3 className="mt-3 text-base font-bold text-[var(--foreground)]">{goal.title}</h3>
                      {goal.description && (
                        <p className="mt-2 line-clamp-2 text-sm leading-6 text-[var(--foreground-muted)]">
                          {goal.description}
                        </p>
                      )}
                      <div className="mt-4 h-2 rounded-full bg-[var(--surface-200)]">
                        <div
                          className="h-full rounded-full bg-[var(--accent)] transition-all duration-500"
                          style={{ width: `${goal.progress}%` }}
                        />
                      </div>
                    </button>
                  ))
                ) : (
                  <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
                    이 상태의 목표가 아직 없습니다.
                  </div>
                )}
              </div>
            </article>
          );
        })}
      </section>

      {isDrawerOpen && (
        <>
          <div className="fixed inset-0 z-[100] bg-black/35 backdrop-blur-sm" onClick={() => setIsDrawerOpen(false)} />
          <aside className="fixed right-0 top-0 bottom-0 z-[101] w-full max-w-lg overflow-y-auto bg-[var(--background)] shadow-2xl">
            <div className="p-6 sm:p-8">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="metric-label">{editingGoal ? "목표 수정" : "새 목표"}</p>
                  <h2 className="mt-2 text-3xl font-extrabold tracking-tight text-[var(--foreground)]">
                    {editingGoal ? "목표 정보 수정" : "새 목표 만들기"}
                  </h2>
                </div>
                <button type="button" onClick={() => setIsDrawerOpen(false)} className="icon-button" aria-label="닫기">
                  <Plus className="h-4 w-4 rotate-45" />
                </button>
              </div>

              <div className="mt-8 space-y-5">
                <div>
                  <label className="field-label" htmlFor="goal-title">
                    제목
                  </label>
                  <input
                    id="goal-title"
                    className="input-field mt-2"
                    value={form.title ?? ""}
                    onChange={(event) => setForm((current) => ({ ...current, title: event.target.value }))}
                    placeholder="예: 이번 주 기획 문서 마무리"
                  />
                </div>

                <div>
                  <label className="field-label" htmlFor="goal-description">
                    설명
                  </label>
                  <textarea
                    id="goal-description"
                    className="textarea-field mt-2"
                    value={form.description ?? ""}
                    onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
                    placeholder="달성 기준이나 메모를 적어 주세요."
                  />
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <div>
                    <label className="field-label" htmlFor="goal-category">
                      카테고리
                    </label>
                    <select
                      id="goal-category"
                      className="select-field mt-2"
                      value={form.category}
                      onChange={(event) =>
                        setForm((current) => ({
                          ...current,
                          category: event.target.value as GoalCategory,
                        }))
                      }
                    >
                      {Object.entries(GOAL_CATEGORY_LABELS).map(([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div>
                    <label className="field-label" htmlFor="goal-status">
                      상태
                    </label>
                    <select
                      id="goal-status"
                      className="select-field mt-2"
                      value={form.status}
                      onChange={(event) =>
                        setForm((current) => ({
                          ...current,
                          status: event.target.value as GoalStatus,
                        }))
                      }
                    >
                      {Object.entries(GOAL_STATUS_LABELS).map(([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                <div>
                  <div className="flex items-center justify-between gap-3">
                    <label className="field-label" htmlFor="goal-progress">
                      진척도
                    </label>
                    <span className="text-sm font-semibold text-[var(--accent)]">{form.progress ?? 0}%</span>
                  </div>
                  <input
                    id="goal-progress"
                    className="mt-3 w-full accent-[var(--accent)]"
                    type="range"
                    min="0"
                    max="100"
                    step="5"
                    value={form.progress ?? 0}
                    onChange={(event) =>
                      setForm((current) => ({
                        ...current,
                        progress: Number(event.target.value),
                      }))
                    }
                  />
                </div>
              </div>

              <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                {editingGoal ? (
                  <button type="button" onClick={() => void handleDelete()} className="btn-ghost border border-[var(--border)] text-[var(--error)]">
                    <Trash2 className="h-4 w-4" />
                    삭제
                  </button>
                ) : (
                  <span />
                )}

                <div className="flex flex-col gap-3 sm:flex-row">
                  <button type="button" onClick={() => setIsDrawerOpen(false)} className="btn-secondary">
                    취소
                  </button>
                  <button type="button" onClick={() => void handleSubmit()} disabled={goals.isMutating} className="btn-primary">
                    {goals.isMutating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Target className="h-4 w-4" />}
                    {editingGoal ? "변경 저장" : "목표 추가"}
                  </button>
                </div>
              </div>
            </div>
          </aside>
        </>
      )}
    </div>
  );
};
