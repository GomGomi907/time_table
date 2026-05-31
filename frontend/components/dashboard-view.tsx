"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";

import { AppShell } from "@/components/app-shell";
import { SectionHeader } from "@/components/section-header";
import { SuggestionReviewCard } from "@/components/suggestion-review-card";
import { api } from "@/lib/api";
import { formatClockValue, formatServiceCopy } from "@/lib/format";
import {
  CATEGORY_LABELS,
  DAY_FULL_LABELS,
  getCurrentDayName,
  getCurrentMinutes,
  getDailyBlocks,
  isBlockActive,
  minutesFromClock,
} from "@/lib/schedule";
import {
  DashboardMetrics,
  FocusCurrentView,
  Goal,
  RescheduleSuggestion,
  ScheduleBlock,
  SyncStatusEnvelope,
  WeekScheduleResponse,
} from "@/lib/types";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { useAppStore } from "@/stores/app-store";

interface DashboardData {
  week: WeekScheduleResponse | null;
  goals: Goal[];
  focus: FocusCurrentView | null;
  sync: SyncStatusEnvelope | null;
  suggestions: RescheduleSuggestion[];
  metrics: DashboardMetrics;
}

const EMPTY_STATE: DashboardData = {
  week: null,
  goals: [],
  focus: null,
  sync: null,
  suggestions: [],
  metrics: {
    averageGoalProgress: 0,
    weeklyShapeScore: 0,
    scheduleBlockCount: 0,
    growthBlockCount: 0,
    topGoal: null,
  },
};

function sortScheduleBlocks(blocks: ScheduleBlock[]) {
  return [...blocks].sort((left, right) => minutesFromClock(left.startTime) - minutesFromClock(right.startTime));
}

function dedupeTodayBlocks(blocks: ScheduleBlock[]) {
  const seen = new Set<string>();
  return blocks.filter((block) => {
    const key = [
      block.startTime,
      block.endTime,
      block.activity.trim(),
      block.category,
      block.note?.trim() ?? "",
    ].join("|");
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function pickBriefingScheduleRows(blocks: ScheduleBlock[], currentMinutes: number, limit = 4) {
  if (blocks.length <= limit) {
    return blocks;
  }

  const anchorIndex = blocks.findIndex(
    (block) => isBlockActive(block, currentMinutes) || minutesFromClock(block.startTime) > currentMinutes,
  );
  const desiredStart = anchorIndex < 0 ? blocks.length - limit : anchorIndex - 1;
  const boundedStart = Math.max(0, Math.min(desiredStart, blocks.length - limit));

  return blocks.slice(boundedStart, boundedStart + limit);
}

function buildDayHeadline(blocks: ScheduleBlock[]) {
  if (!blocks.length) {
    return "오늘 예정된 일정이 없습니다.";
  }

  return `오늘 일정은 ${blocks.length}개입니다.`;
}

function buildBriefingLine({
  blocks,
  liveOrNextBlock,
}: {
  blocks: ScheduleBlock[];
  liveOrNextBlock: ScheduleBlock | null;
}) {
  if (!blocks.length) {
    return "주간 일정에서 오늘 시간을 먼저 배치할 수 있습니다.";
  }

  if (liveOrNextBlock) {
    return `지금/다음 ${formatClockValue(liveOrNextBlock.startTime)} · ${formatServiceCopy(liveOrNextBlock.activity)}`;
  }

  return `오늘 일정 ${blocks.length}개 · 남은 일정 없음`;
}

function buildPrimaryAction({
  pendingSuggestion,
  currentItem,
  recommendedTask,
}: {
  pendingSuggestion: RescheduleSuggestion | null;
  currentItem: FocusCurrentView["currentItem"];
  recommendedTask: FocusCurrentView["recommendedTasks"][number] | null;
}) {
  if (pendingSuggestion) {
    return {
      label: "변경 확인",
      href: "/schedule",
      detail: "검토할 변경이 있습니다.",
    };
  }

  if (currentItem || recommendedTask) {
    return {
      label: "실행 모드 시작",
      href: "/focus",
      detail: currentItem
        ? `${formatServiceCopy(currentItem.title)}을 이어서 진행할 수 있습니다.`
        : `${formatServiceCopy(recommendedTask?.title ?? "할 일")}부터 시작할 수 있습니다.`,
    };
  }

  return {
    label: "주간 일정 보기",
    href: "/schedule",
    detail: "오늘 시간표를 정리할 수 있습니다.",
  };
}

export function DashboardView() {
  const { session, refreshSession } = useSessionBootstrap();
  const showNotice = useAppStore((state) => state.showNotice);
  const [data, setData] = useState<DashboardData>(EMPTY_STATE);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [isMutating, setIsMutating] = useState(false);

  async function loadDashboard() {
    if (!session?.authenticated) {
      return;
    }

    try {
      setStatus("loading");
      setError(null);
      const summary = await api.getDashboardSummary();
      setData(summary.data);
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
    if (isMutating) {
      return;
    }

    try {
      setIsMutating(true);
      await action();
      showNotice({
        tone: "success",
        title: successTitle,
      });
      await Promise.all([loadDashboard(), refreshSession({ silent: true })]);
    } catch (mutationError) {
      showNotice({
        tone: "error",
        title: errorTitle,
        detail:
          mutationError instanceof Error
            ? mutationError.message
            : "잠시 후 다시 시도하면 됩니다.",
      });
    } finally {
      setIsMutating(false);
    }
  }

  const pendingSuggestion =
    data.suggestions.find((suggestion) => suggestion.status === "pending") ?? null;
  const todayName = getCurrentDayName(session?.timezone);
  const todayLabel = DAY_FULL_LABELS[todayName] ?? "오늘";
  const currentMinutes = getCurrentMinutes(session?.timezone);
  const currentItem = data.focus?.currentItem ?? null;
  const recommendedTask = data.focus?.recommendedTasks[0] ?? null;
  const fullTodaySchedule = useMemo(() => {
    const blocks = getDailyBlocks(data.week, todayName);
    const nonSleepBlocks = blocks.filter((block) => block.category !== "SLEEP");
    return sortScheduleBlocks(dedupeTodayBlocks(nonSleepBlocks.length ? nonSleepBlocks : blocks));
  }, [data.week, todayName]);
  const dayHeadline = useMemo(() => buildDayHeadline(fullTodaySchedule), [fullTodaySchedule]);
  const primaryAction = buildPrimaryAction({ pendingSuggestion, currentItem, recommendedTask });
  const liveOrNextBlock =
    fullTodaySchedule.find((block) => isBlockActive(block, currentMinutes)) ??
    fullTodaySchedule.find((block) => minutesFromClock(block.startTime) > currentMinutes) ??
    fullTodaySchedule[0] ??
    null;
  const nextShiftDescription = liveOrNextBlock
    ? buildBriefingLine({ blocks: fullTodaySchedule, liveOrNextBlock })
    : buildBriefingLine({ blocks: fullTodaySchedule, liveOrNextBlock: null });
  const briefingScheduleRows = useMemo(
    () => pickBriefingScheduleRows(fullTodaySchedule, currentMinutes),
    [fullTodaySchedule, currentMinutes],
  );
  const hiddenScheduleCount = Math.max(fullTodaySchedule.length - briefingScheduleRows.length, 0);

  return (
    <AppShell
      eyebrow="오늘"
      title="오늘 일정"
      description="오늘 일정과 바로 시작할 일을 한눈에 보여줍니다."
      actions={
        <button className="ghost-btn" disabled={status === "loading"} onClick={() => void loadDashboard()}>
          {status === "loading" ? "새로고침 중..." : "새로고침"}
        </button>
      }
    >
      {!data.week && !data.focus && data.goals.length === 0 && status === "loading" ? (
        <section className="surface-card empty-state">
          <strong>오늘 일정을 준비하는 중입니다.</strong>
          <p>오늘 예정된 일정부터 확인하고 있습니다.</p>
        </section>
      ) : null}

      {!data.week && !data.focus && data.goals.length === 0 && status === "error" ? (
        <section className="surface-card empty-state">
          <strong>오늘 일정을 불러오지 못했습니다.</strong>
          <p>{error ?? "서비스 응답 확인이 필요합니다."}</p>
          <button className="solid-btn" data-testid="status-retry-action" onClick={() => void loadDashboard()}>
            다시 불러오기
          </button>
        </section>
      ) : null}

      {data.week || data.focus || data.goals.length ? (
        <>
          <section
            className={`today-briefing-shell ${pendingSuggestion ? "has-pending-approval" : ""}`}
            aria-label="오늘 일정 핵심"
          >
            <article className="surface-card today-flow-card">
              <div className="today-flow-hero">
                <div className="today-flow-copy">
                  <p className="eyebrow">{todayLabel} 예정</p>
                  <h2>{dayHeadline}</h2>
                  <p>{nextShiftDescription}</p>
                </div>
                <div className="today-action-strip" aria-label="지금 할 일 요약">
                  <span>지금 할 일</span>
                  <strong>
                    {currentItem
                      ? formatServiceCopy(currentItem.title)
                      : recommendedTask
                        ? formatServiceCopy(recommendedTask.title)
                        : liveOrNextBlock
                          ? formatServiceCopy(liveOrNextBlock.activity)
                          : "주간 일정 정리"}
                  </strong>
                  <p>{primaryAction.detail}</p>
                </div>
                <Link className="solid-btn link-btn today-primary-action" data-testid="dashboard-primary-action" href={primaryAction.href}>
                  {primaryAction.label}
                </Link>
              </div>
            </article>

            <article className="surface-card today-schedule-card today-schedule-panel">
              <SectionHeader
                eyebrow="오늘 보기"
                title="오늘 일정 핵심"
                trailing={<span className="accent-pill">전체 {fullTodaySchedule.length}개</span>}
              />
              <div className="today-schedule-list">
                {briefingScheduleRows.length ? (
                  briefingScheduleRows.map((block) => {
                    const isLive = isBlockActive(block, currentMinutes);
                    return (
                      <div className={`today-schedule-row ${isLive ? "live" : ""}`} key={block.id}>
                        <span className="time-chip">
                          {formatClockValue(block.startTime)}–{formatClockValue(block.endTime)}
                        </span>
                        <div>
                          <strong>{formatServiceCopy(block.activity)}</strong>
                          <p>
                            {CATEGORY_LABELS[block.category] ?? block.category}
                            {isLive ? " · 지금 진행 중" : ""}
                          </p>
                        </div>
                      </div>
                    );
                  })
                ) : (
                  <div className="empty-state subtle">
                    <strong>오늘 일정이 아직 없습니다.</strong>
                    <p>주간 일정에서 시간 블록을 추가하면 여기에 표시됩니다.</p>
                  </div>
                )}
              </div>
              {hiddenScheduleCount ? (
                <div className="today-schedule-more">
                  <span>나머지 {hiddenScheduleCount}개 일정</span>
                  <Link className="ghost-btn link-btn" href="/schedule">
                    전체 보기
                  </Link>
                </div>
              ) : null}
            </article>

            {pendingSuggestion ? (
              <SuggestionReviewCard
                className="surface-card ai-approval-card pending"
                isPending={isMutating}
                suggestion={pendingSuggestion}
                titleElement="h2"
                onApply={() =>
                  void withDashboardMutation(
                    () => api.applySuggestion(pendingSuggestion.id),
                    "변경을 적용했습니다.",
                    "변경 처리에 실패했습니다.",
                  )
                }
                onReject={() =>
                  void withDashboardMutation(
                    () => api.rejectSuggestion(pendingSuggestion.id),
                    "변경을 보류했습니다.",
                    "변경 처리에 실패했습니다.",
                  )
                }
              />
            ) : null}
          </section>


        </>
      ) : null}
    </AppShell>
  );
}
