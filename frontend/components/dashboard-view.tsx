"use client";

import { useEffect, useState, useTransition } from "react";

import { AppShell } from "@/components/app-shell";
import { FocusActionBar } from "@/components/focus-action-bar";
import { FocusRailCard } from "@/components/focus-rail-card";
import { SectionHeader } from "@/components/section-header";
import { api } from "@/lib/api";
import { formatClockValue, formatDateTime, formatPercent, formatRelativeMinutes } from "@/lib/format";
import {
  CATEGORY_LABELS,
  DAY_FULL_LABELS,
  getCurrentDayName,
  getCurrentMinutes,
  getDashboardFlow,
  getFallbackFocusBlock,
  getNextScheduleBlocks,
  getWeeklyCompletionScore,
  isBlockActive,
} from "@/lib/schedule";
import { FocusCurrentView, Goal, RescheduleSuggestion, SyncStatusEnvelope, WeekScheduleResponse } from "@/lib/types";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { useAppStore } from "@/stores/app-store";

interface DashboardData {
  week: WeekScheduleResponse | null;
  goals: Goal[];
  focus: FocusCurrentView | null;
  sync: SyncStatusEnvelope | null;
  suggestions: RescheduleSuggestion[];
}

const EMPTY_STATE: DashboardData = {
  week: null,
  goals: [],
  focus: null,
  sync: null,
  suggestions: [],
};

function describeFocusState(view: FocusCurrentView | null) {
  switch (view?.focusState) {
    case "ACTIVE_EVENT":
    case "ACTIVE_TASK":
      return "진행 중";
    case "AWAITING_END_CONFIRMATION":
      return "종료 확인 대기";
    case "UPCOMING_EVENT_READY":
      return "곧 시작";
    case "RESCHEDULE_PENDING":
      return "재조정 필요";
    default:
      return "대기 중";
  }
}

function focusTitle(view: FocusCurrentView | null, fallbackTitle: string | null) {
  if (view?.currentItem) {
    return view.currentItem.title;
  }
  if (view?.recommendedTasks[0]) {
    return view.recommendedTasks[0].title;
  }
  return fallbackTitle ?? "지금 실행할 항목이 없습니다.";
}

export function DashboardView() {
  const { session } = useSessionBootstrap();
  const showNotice = useAppStore((state) => state.showNotice);
  const [data, setData] = useState<DashboardData>(EMPTY_STATE);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  async function loadDashboard() {
    if (!session?.authenticated) {
      return;
    }

    try {
      setStatus("loading");
      setError(null);
      const [week, goals, focus, sync, suggestions] = await Promise.all([
        api.getWeekSchedule(),
        api.getGoals(),
        api.getFocusCurrent(),
        api.getSyncStatus(),
        api.getSuggestions(),
      ]);

      setData({
        week,
        goals: goals.data,
        focus: focus.data,
        sync,
        suggestions: suggestions.data,
      });
      setStatus("ready");
    } catch (loadError) {
      setStatus("error");
      setError(loadError instanceof Error ? loadError.message : "데이터를 불러오지 못했습니다.");
    }
  }

  useEffect(() => {
    if (!session?.authenticated) {
      return;
    }

    void loadDashboard();
  }, [session?.authenticated]);

  async function withDashboardMutation(
    action: () => Promise<unknown>,
    successTitle: string,
    errorTitle: string,
  ) {
    try {
      await action();
      showNotice({
        tone: "success",
        title: successTitle,
      });
      await loadDashboard();
    } catch (mutationError) {
      showNotice({
        tone: "error",
        title: errorTitle,
        detail:
          mutationError instanceof Error
            ? mutationError.message
            : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  const averageGoalProgress =
    data.goals.length > 0
      ? Math.round(data.goals.reduce((sum, goal) => sum + goal.progress, 0) / data.goals.length)
      : 0;
  const topGoal = [...data.goals].sort((left, right) => right.progress - left.progress)[0] ?? null;
  const pendingSuggestion =
    data.suggestions.find((suggestion) => suggestion.status === "pending") ?? null;
  const flowBlocks = getDashboardFlow(data.week, session?.timezone);
  const weeklyShapeScore = data.week ? getWeeklyCompletionScore(data.week.week) : 0;
  const currentMinutes = getCurrentMinutes(session?.timezone);
  const fallbackBlock = getFallbackFocusBlock(data.week, session?.timezone);
  const nextScheduleBlocks = getNextScheduleBlocks(data.week, 2, session?.timezone);
  const currentItem = data.focus?.currentItem ?? null;
  const nextItem = data.focus?.nextItem ?? null;
  const primaryTitle = focusTitle(data.focus, fallbackBlock?.activity ?? null);
  const nextShiftTitle =
    nextItem?.title ?? fallbackBlock?.activity ?? data.focus?.recommendedTasks[0]?.title ?? "다음 전환 없음";
  const nextShiftDescription = nextItem
    ? `${formatDateTime(nextItem.startAt, session?.timezone)} 시작 예정`
    : fallbackBlock
      ? `${formatClockValue(fallbackBlock.startTime)} 시작 예정`
      : data.focus?.recommendedTasks[0]
        ? `예상 소요 ${formatRelativeMinutes(data.focus.recommendedTasks[0].estimatedMinutes)}`
        : "일정 또는 추천 태스크가 비어 있습니다.";

  function handleDeleteCurrentItem() {
    if (!currentItem) {
      return;
    }

    const itemLabel = currentItem.type.toLowerCase() === "task" ? "할 일" : "일정";
    const confirmed = window.confirm(`현재 ${itemLabel}을 삭제할까요? 이 작업은 되돌릴 수 없습니다.`);
    if (!confirmed) {
      return;
    }

    startTransition(() =>
      void withDashboardMutation(
        () => api.deleteFocusItem(currentItem.type, currentItem.id),
        `현재 ${itemLabel}을 삭제했습니다.`,
        "포커스 작업을 처리하지 못했습니다.",
      ),
    );
  }

  return (
    <AppShell
      eyebrow="대시보드"
      title="대시보드"
      description="현재 집중, 오늘 흐름, 주간 목표를 한 화면에서 같이 보는 홈 화면입니다."
      actions={
        <button className="ghost-btn" onClick={() => void loadDashboard()}>
          새로고침
        </button>
      }
    >
      {!data.week && !data.focus && data.goals.length === 0 && status === "loading" ? (
        <section className="surface-card empty-state">
          <strong>대시보드를 불러오는 중입니다.</strong>
          <p>현재 집중, 시간표, 목표 데이터를 함께 준비하고 있습니다.</p>
        </section>
      ) : null}

      {!data.week && !data.focus && data.goals.length === 0 && status === "error" ? (
        <section className="surface-card empty-state">
          <strong>대시보드 데이터를 불러오지 못했습니다.</strong>
          <p>{error ?? "백엔드 응답을 다시 확인해 주세요."}</p>
          <button className="solid-btn" onClick={() => void loadDashboard()}>
            다시 불러오기
          </button>
        </section>
      ) : null}

      {data.week || data.focus || data.goals.length ? (
        <>
      <section className="stats-row compact">
        <article className="stat-card">
          <span>현재 집중 상태</span>
          <strong>{describeFocusState(data.focus)}</strong>
          <p>
            {data.focus?.remainingMinutes != null
              ? `다음 전환까지 ${formatRelativeMinutes(data.focus.remainingMinutes)}`
              : "현재 활성 이벤트 없음"}
          </p>
        </article>
        <article className="stat-card">
          <span>목표 진행</span>
          <strong>{formatPercent(averageGoalProgress)}</strong>
          <p>
            {topGoal
              ? `${topGoal.title} · 주간 블록 구성도 ${formatPercent(weeklyShapeScore)}`
              : "목표를 불러오는 중"}
          </p>
        </article>
      </section>

      <section className="focus-layout">
        <section className="focus-primary">
          <p className="eyebrow">현재 집중</p>
          <h1>{primaryTitle}</h1>
          <p className="focus-copy">
            {currentItem
              ? `${formatClockValue(currentItem.startAt, session?.timezone)}부터 진행 중이며, 남은 시간은 ${formatRelativeMinutes(
                  data.focus?.remainingMinutes,
                )}입니다.`
              : data.focus?.recommendedTasks[0]
                ? "활성 이벤트는 없지만 바로 시작할 수 있는 추천 태스크가 준비되어 있습니다."
                : fallbackBlock
                  ? "활성 이벤트는 없어서 시간표 기준 다음 블록을 안내합니다. 실제 포커스 상태는 이벤트나 태스크가 있어야 활성화됩니다."
                  : "아직 실행할 이벤트와 추천 태스크가 모두 비어 있습니다."}
          </p>

          <div className="timer-ring">
            <div className="timer-inner">
              <span className="timer-label">남은 시간</span>
              <strong>{formatRelativeMinutes(data.focus?.remainingMinutes)}</strong>
              <p>
                {currentItem
                  ? `${formatClockValue(currentItem.startAt, session?.timezone)} - ${formatClockValue(currentItem.endAt, session?.timezone)}`
                  : fallbackBlock
                    ? `${formatClockValue(fallbackBlock.startTime)} - ${formatClockValue(
                        fallbackBlock.endTime,
                      )}`
                    : "계획 없음"}
              </p>
            </div>
          </div>

          <FocusActionBar
            currentItem={currentItem}
            hasRecommendedTask={Boolean(data.focus?.recommendedTasks[0])}
            isPending={isPending}
            onCompleteCurrent={
              currentItem
                ? () =>
                    startTransition(() =>
                      void withDashboardMutation(
                        () => api.completeFocusItem(currentItem.type, currentItem.id),
                        "현재 포커스 항목을 완료 처리했습니다.",
                        "포커스 작업을 처리하지 못했습니다.",
                      ),
                    )
                : null
            }
            onPostponeCurrent={
              currentItem && currentItem.type.toLowerCase() === "event"
                ? () =>
                    startTransition(() =>
                      void withDashboardMutation(
                        () => api.postponeFocusItem(currentItem.type, currentItem.id, "다음 블록과 충돌 가능성"),
                        "현재 항목을 미루고 재조율 대상으로 표시했습니다.",
                        "포커스 작업을 처리하지 못했습니다.",
                      ),
                    )
                : null
            }
            onExtendCurrent={
              currentItem && currentItem.type.toLowerCase() === "event"
                ? () =>
                    startTransition(() =>
                      void withDashboardMutation(
                        () => api.extendFocusItem(currentItem.type, currentItem.id, 15),
                        "15분 연장을 요청했습니다.",
                        "포커스 작업을 처리하지 못했습니다.",
                      ),
                    )
                : null
            }
            onDeleteCurrent={currentItem ? handleDeleteCurrentItem : null}
            onStartRecommended={
              data.focus?.recommendedTasks[0]
                ? () =>
                    startTransition(() =>
                      void withDashboardMutation(
                        () => api.startRecommendedTask(data.focus?.recommendedTasks[0].id ?? ""),
                        "추천 태스크를 시작했습니다.",
                        "포커스 작업을 처리하지 못했습니다.",
                      ),
                    )
                : null
            }
          />
        </section>

        <aside className="focus-sidebar">
          <FocusRailCard
            currentItem={currentItem}
            recommendedTasks={data.focus?.recommendedTasks ?? []}
            fallbackBlock={fallbackBlock}
            nextBlocks={nextScheduleBlocks}
            pendingSuggestion={pendingSuggestion}
            pendingConflictCount={data.sync?.meta.pendingConflictCount ?? 0}
            isPending={isPending}
            onRejectSuggestion={
              pendingSuggestion
                ? () =>
                    startTransition(() =>
                      void withDashboardMutation(
                        () => api.rejectSuggestion(pendingSuggestion.id),
                        "제안을 나중으로 미뤘습니다.",
                        "제안 처리에 실패했습니다.",
                      ),
                    )
                : null
            }
            onApplySuggestion={
              pendingSuggestion
                ? () =>
                    startTransition(() =>
                      void withDashboardMutation(
                        () => api.applySuggestion(pendingSuggestion.id),
                        "대기 중이던 재조율 제안을 반영했습니다.",
                        "제안 처리에 실패했습니다.",
                      ),
                    )
                : null
            }
          />
        </aside>
      </section>

      <section className="dashboard-lower-grid">
        <article className="surface-card timeline-card">
          <SectionHeader
            eyebrow="오늘 흐름"
            title={DAY_FULL_LABELS[getCurrentDayName(session?.timezone)] ?? "오늘"}
            trailing={<span className="accent-pill">기본 시간표 기준</span>}
          />

          <div className="timeline-list">
            {flowBlocks.length ? (
              flowBlocks.map((block) => (
                <div
                  key={block.id}
                  className={`timeline-item ${isBlockActive(block, currentMinutes) ? "live" : ""}`}
                >
                  <span className="time-chip">{formatClockValue(block.startTime)}</span>
                  <div>
                    <strong>{block.activity}</strong>
                    <p>
                      {formatClockValue(block.startTime)} - {formatClockValue(block.endTime)}
                      {" · "}
                      {CATEGORY_LABELS[block.category] ?? block.category}
                    </p>
                  </div>
                </div>
              ))
            ) : (
              <div className="empty-state subtle">
                <strong>오늘 시간표가 아직 없습니다.</strong>
                <p>주간 플래너에서 블록을 추가하면 이 영역이 자동으로 채워집니다.</p>
              </div>
            )}
          </div>
        </article>

        <article className="surface-card">
          <SectionHeader eyebrow="목표" title="이번 주 목표" />

          <div className="goal-stack">
            {data.goals.length ? (
              data.goals.slice(0, 3).map((goal) => (
                <div className="goal-row" key={goal.id}>
                  <div className="goal-copy">
                    <strong>{goal.title}</strong>
                    <span>{formatPercent(goal.progress)}</span>
                  </div>
                  <div className="progress-bar">
                    <span style={{ width: `${goal.progress}%` }} />
                  </div>
                </div>
              ))
            ) : (
              <div className="empty-state subtle">
                <strong>표시할 목표가 없습니다.</strong>
                <p>백엔드 기본 목표 seed가 있으면 이 카드에 바로 반영됩니다.</p>
              </div>
            )}
          </div>
        </article>
      </section>
        </>
      ) : null}
    </AppShell>
  );
}
