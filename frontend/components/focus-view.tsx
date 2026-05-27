"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { AppShell } from "@/components/app-shell";
import { FocusActionBar } from "@/components/focus-action-bar";
import { FocusRailCard } from "@/components/focus-rail-card";
import { api } from "@/lib/api";
import { formatClockValue, formatRelativeMinutes, formatServiceCopy } from "@/lib/format";
import {
  DAY_FULL_LABELS,
  DAY_ORDER,
  durationInMinutes,
  getCurrentDayName,
  getCurrentMinutes,
  getFallbackFocusBlock,
  getNextScheduleBlocks,
  minutesFromClock,
} from "@/lib/schedule";
import {
  FocusCurrentView,
  FocusScheduleBlock,
  RescheduleSuggestion,
  ScheduleBlock,
  WeekScheduleResponse,
} from "@/lib/types";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { useAppStore } from "@/stores/app-store";

interface FocusData {
  focus: FocusCurrentView | null;
  week: WeekScheduleResponse | null;
  suggestions: RescheduleSuggestion[];
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

function isFocusCurrentViewPayload(value: unknown): value is FocusCurrentView {
  return (
    value !== null &&
    typeof value === "object" &&
    "focusState" in value &&
    "recommendedTasks" in value &&
    Array.isArray((value as { recommendedTasks?: unknown }).recommendedTasks)
  );
}

function focusScheduleBlock(
  view: FocusCurrentView | null,
  localFallback: FocusScheduleBlock | ScheduleBlock | null,
) {
  return view?.scheduleContext?.currentBlock ?? view?.scheduleContext?.nextBlock ?? localFallback;
}

function dayOffsetFromToday(dayOfWeek: string | undefined, todayName: string) {
  if (!dayOfWeek) {
    return 0;
  }

  const todayIndex = DAY_ORDER.indexOf(todayName as (typeof DAY_ORDER)[number]);
  const targetIndex = DAY_ORDER.indexOf(dayOfWeek as (typeof DAY_ORDER)[number]);
  if (todayIndex === -1 || targetIndex === -1) {
    return 0;
  }

  return (targetIndex - todayIndex + DAY_ORDER.length) % DAY_ORDER.length;
}

function minutesUntilBlockStart(
  block: FocusScheduleBlock | ScheduleBlock,
  todayName: string,
  currentMinutes: number,
) {
  const offsetDays = dayOffsetFromToday("dayOfWeek" in block ? block.dayOfWeek : undefined, todayName);
  let minutes = offsetDays * 24 * 60 + minutesFromClock(block.startTime) - currentMinutes;
  if (minutes <= 0) {
    minutes += DAY_ORDER.length * 24 * 60;
  }
  return minutes;
}

function remainingMinutesInBlock(block: FocusScheduleBlock | ScheduleBlock, currentMinutes: number) {
  const start = minutesFromClock(block.startTime);
  const duration = durationInMinutes(block.startTime, block.endTime);
  const end = start + duration;
  const normalizedNow = currentMinutes < start && end > 24 * 60 ? currentMinutes + 24 * 60 : currentMinutes;
  return Math.max(0, end - normalizedNow);
}

function buildTimerPresentation({
  currentItem,
  recommendedTask,
  fallbackBlock,
  focus,
  todayName,
  currentMinutes,
  timezone,
}: {
  currentItem: FocusCurrentView["currentItem"];
  recommendedTask: FocusCurrentView["recommendedTasks"][number] | null;
  fallbackBlock: FocusScheduleBlock | ScheduleBlock | null;
  focus: FocusCurrentView | null;
  todayName: string;
  currentMinutes: number;
  timezone?: string;
}) {
  if (currentItem) {
    const remainingMinutes = currentItem.remainingMinutes ?? focus?.remainingMinutes ?? 0;
    return {
      label: "남은 시간",
      value: formatRelativeMinutes(remainingMinutes),
      context: `${formatClockValue(currentItem.startAt, timezone)} - ${formatClockValue(currentItem.endAt, timezone)}`,
    };
  }

  if (recommendedTask) {
    return {
      label: "지금 시작",
      value: formatRelativeMinutes(recommendedTask.estimatedMinutes),
      context: "바로 시작할 수 있습니다.",
    };
  }

  if (fallbackBlock) {
    const isActiveScheduleBlock = focus?.scheduleContext?.state === "ACTIVE_BLOCK";
    const value = isActiveScheduleBlock
      ? formatRelativeMinutes(remainingMinutesInBlock(fallbackBlock, currentMinutes))
      : `${formatRelativeMinutes(minutesUntilBlockStart(fallbackBlock, todayName, currentMinutes))} 후`;
    const dayLabel =
      "dayOfWeek" in fallbackBlock && fallbackBlock.dayOfWeek !== todayName
        ? `${DAY_FULL_LABELS[fallbackBlock.dayOfWeek] ?? fallbackBlock.dayOfWeek} `
        : "";

    return {
      label: isActiveScheduleBlock ? "남은 시간" : "다음 일정",
      value,
      context: `${dayLabel}${formatClockValue(fallbackBlock.startTime)} - ${formatClockValue(fallbackBlock.endTime)}`,
    };
  }

  return {
    label: "실행 상태",
    value: "대기",
    context: "오늘 바로 실행할 항목이 없습니다.",
  };
}

export function FocusView() {
  const { session, refreshSession } = useSessionBootstrap();
  const showNotice = useAppStore((state) => state.showNotice);
  const [data, setData] = useState<FocusData>({
    focus: null,
    week: null,
    suggestions: [],
  });
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [isMutating, setIsMutating] = useState(false);

  async function loadFocusPage() {
    if (!session?.authenticated) {
      return;
    }

    try {
      setStatus("loading");
      const [focus, week, suggestions] = await Promise.all([
        api.getFocusCurrent(),
        api.getWeekSchedule(),
        api.getSuggestions(),
      ]);

      setData({
        focus: focus.data,
        week,
        suggestions: suggestions.data,
      });
      setStatus("ready");
      setError(null);
    } catch (loadError) {
      setStatus("error");
      setError(loadError instanceof Error ? loadError.message : "집중 화면을 준비하지 못했습니다.");
    }
  }

  useEffect(() => {
    if (!session?.authenticated) {
      return;
    }

    void loadFocusPage();
  }, [session?.authenticated]);

  async function withFocusMutation(
    action: () => Promise<unknown>,
    title: string,
    options: { syncFocusFromResponse?: boolean } = {},
  ) {
    if (isMutating) {
      return;
    }

    try {
      setIsMutating(true);
      const shouldSyncFocus = options.syncFocusFromResponse ?? true;
      const response = await action();
      if (
        shouldSyncFocus &&
        response &&
        typeof response === "object" &&
        "data" in response
      ) {
        const responseData = (response as { data: unknown }).data;
        if (isFocusCurrentViewPayload(responseData)) {
          setData((current) => ({
            ...current,
            focus: responseData,
          }));
        }
      }
      showNotice({
        tone: "success",
        title,
      });
      await Promise.all([loadFocusPage(), refreshSession({ silent: true })]);
    } catch (mutationError) {
      showNotice({
        tone: "error",
        title: "집중 항목을 처리하지 못했습니다.",
        detail:
          mutationError instanceof Error
            ? mutationError.message
            : "잠시 후 다시 시도해 주세요.",
      });
    } finally {
      setIsMutating(false);
    }
  }

  const localFallbackBlock = getFallbackFocusBlock(data.week, session?.timezone);
  const fallbackBlock = focusScheduleBlock(data.focus, localFallbackBlock);
  const nextScheduleBlocks = getNextScheduleBlocks(data.week, 2, session?.timezone);
  const todayName = getCurrentDayName(session?.timezone);
  const currentMinutes = getCurrentMinutes(session?.timezone);
  const currentItem = data.focus?.currentItem ?? null;
  const recommendedTask = data.focus?.recommendedTasks[0] ?? null;
  const actionableScheduleBlock = !currentItem
    ? data.focus?.scheduleContext?.currentBlock ?? data.focus?.scheduleContext?.nextBlock ?? null
    : null;
  const canCompleteScheduleBlock =
    data.focus?.scheduleContext?.state === "ACTIVE_BLOCK" && Boolean(data.focus.scheduleContext.currentBlock);
  const pendingSuggestion = data.suggestions.find((suggestion) => suggestion.status === "pending") ?? null;
  const pendingConflictCount = data.suggestions.filter((suggestion) => suggestion.status === "pending").length;
  const primaryTitle = focusTitle(data.focus, fallbackBlock?.activity ?? null);
  const timerPresentation = buildTimerPresentation({
    currentItem,
    recommendedTask,
    fallbackBlock,
    focus: data.focus,
    todayName,
    currentMinutes,
    timezone: session?.timezone,
  });

  function handleDeleteCurrentItem() {
    if (!currentItem) {
      return;
    }

    const itemLabel = currentItem.type.toLowerCase() === "task" ? "할 일" : "일정";
    const confirmed = window.confirm(`현재 ${itemLabel}을 삭제할까요? 이 작업은 되돌릴 수 없습니다.`);
    if (!confirmed) {
      return;
    }

    void withFocusMutation(
      () => api.deleteFocusItem(currentItem.type, currentItem.id),
      `현재 ${itemLabel}을 삭제했습니다.`,
    );
  }

  function handleSuggestionDecision(action: "apply" | "reject") {
    if (!pendingSuggestion) {
      return;
    }
    if (action === "apply" && !pendingSuggestion.executable) {
      showNotice({
        tone: "error",
        title: "적용할 변경이 없습니다.",
      });
      return;
    }

    void withFocusMutation(
      () =>
        action === "apply"
          ? api.applySuggestion(pendingSuggestion.id, "적용")
          : api.rejectSuggestion(pendingSuggestion.id, "보류"),
      action === "apply" ? "변경을 적용했습니다." : "변경을 보류했습니다.",
      { syncFocusFromResponse: false },
    );
  }

  return (
    <AppShell
      immersive
      eyebrow="지금 할 일"
      title="실행 모드"
      description="지금은 한 가지 항목만 처리하세요. 끝나면 완료하거나 미루면 됩니다."
    >
      <section className="focus-mode-stage">
        {!data.week && !data.focus && status === "loading" ? (
          <section className="surface-card empty-state">
            <strong>집중 화면을 준비하는 중입니다.</strong>
            <p>현재 항목과 다음 일정 정보를 불러오고 있습니다.</p>
          </section>
        ) : !data.week && !data.focus && status === "error" ? (
          <section className="surface-card empty-state">
            <strong>집중 데이터를 불러오지 못했습니다.</strong>
            <p>{error ?? "서비스 응답을 다시 확인해 주세요."}</p>
          </section>
        ) : (
          <div className="focus-mode-stack">
            <section className="focus-primary focus-mode-card">
              <Link
                className="ghost-btn link-btn focus-back-btn"
                href="/dashboard"
                aria-label="오늘 화면으로 돌아가기"
              >
                <svg
                  aria-hidden="true"
                  className="focus-back-icon"
                  viewBox="0 0 20 20"
                >
                  <path
                    d="M11.75 4.75 6.5 10l5.25 5.25"
                    fill="none"
                    stroke="currentColor"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth="1.8"
                  />
                </svg>
              </Link>
              <p className="eyebrow">지금 실행</p>
              <h1>{formatServiceCopy(primaryTitle)}</h1>
              <p className="focus-copy">
                {currentItem
                  ? `${formatClockValue(currentItem.startAt, session?.timezone)}부터 진행 중이며, 남은 시간은 ${formatRelativeMinutes(
                      currentItem.remainingMinutes,
                    )}입니다.`
                  : recommendedTask
                    ? "진행 중인 일정은 없지만 바로 시작할 일이 있습니다."
                    : fallbackBlock
                      ? "진행 중인 항목이 없어 다음 일정을 보여드립니다."
                      : "아직 실행할 일정과 할 일이 없습니다."}
              </p>

              <div className="timer-ring">
                <div className="timer-inner">
                  <span className="timer-label">{timerPresentation.label}</span>
                  <strong>{timerPresentation.value}</strong>
                  <p>{timerPresentation.context}</p>
                </div>
              </div>

              <FocusActionBar
                currentItem={currentItem}
                hasRecommendedTask={Boolean(recommendedTask)}
                isPending={isMutating}
                onCompleteCurrent={
                  currentItem
                    ? () =>
                        void withFocusMutation(
                          () => api.completeFocusItem(currentItem.type, currentItem.id),
                          "현재 집중 항목을 완료했습니다.",
                        )
                    : null
                }
                onPostponeCurrent={
                  currentItem && currentItem.type.toLowerCase() === "event"
                    ? () =>
                        void withFocusMutation(
                          () => api.postponeFocusItem(currentItem.type, currentItem.id, "다음 블록과 충돌 가능성"),
                          "현재 항목을 미루고 변경이 필요한 항목으로 표시했습니다.",
                        )
                    : null
                }
                onExtendCurrent={
                  currentItem && currentItem.type.toLowerCase() === "event"
                    ? () =>
                        void withFocusMutation(
                          () => api.extendFocusItem(currentItem.type, currentItem.id, 15),
                          "15분 연장을 요청했습니다.",
                        )
                    : null
                }
                onDeleteCurrent={currentItem ? handleDeleteCurrentItem : null}
                onStartRecommended={
                  recommendedTask
                    ? () =>
                        void withFocusMutation(
                          () => api.startRecommendedTask(recommendedTask.id),
                          "할 일을 시작했습니다.",
                        )
                    : null
                }
              />
              {actionableScheduleBlock ? (
                <div className="focus-action-stack">
                  <div className="focus-actions">
                    {canCompleteScheduleBlock ? (
                      <button
                        className="solid-btn"
                        disabled={isMutating}
                        onClick={() =>
                          void withFocusMutation(
                            () => api.completeScheduleBlock(actionableScheduleBlock.id),
                            "현재 일정 블록을 완료했습니다.",
                          )
                        }
                        type="button"
                      >
                        일정 완료
                      </button>
                    ) : null}
                    <button
                      className={canCompleteScheduleBlock ? "ghost-btn" : "solid-btn"}
                      disabled={isMutating}
                      onClick={() =>
                        void withFocusMutation(
                          () =>
                            api.postponeScheduleBlock(
                              actionableScheduleBlock.id,
                              "실행 모드에서 일정 블록을 미뤘습니다.",
                            ),
                          "일정 블록을 30분 미루고 변경 요청을 만들었습니다.",
                        )
                      }
                      type="button"
                    >
                      미루기
                    </button>
                  </div>
                </div>
              ) : null}
            </section>

            <FocusRailCard
              currentItem={currentItem}
              recommendedTasks={data.focus?.recommendedTasks ?? []}
              fallbackBlock={fallbackBlock}
              nextBlocks={nextScheduleBlocks}
              pendingSuggestion={pendingSuggestion}
              pendingConflictCount={pendingConflictCount}
              isPending={isMutating}
              onApplySuggestion={pendingSuggestion ? () => handleSuggestionDecision("apply") : null}
              onRejectSuggestion={pendingSuggestion ? () => handleSuggestionDecision("reject") : null}
            />
          </div>
        )}
      </section>
    </AppShell>
  );
}
