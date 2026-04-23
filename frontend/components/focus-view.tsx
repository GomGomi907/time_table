"use client";

import Link from "next/link";
import { useEffect, useState, useTransition } from "react";

import { AppShell } from "@/components/app-shell";
import { FocusActionBar } from "@/components/focus-action-bar";
import { FocusRailCard } from "@/components/focus-rail-card";
import { api } from "@/lib/api";
import { formatClockValue, formatRelativeMinutes } from "@/lib/format";
import { getFallbackFocusBlock, getNextScheduleBlocks } from "@/lib/schedule";
import { FocusCurrentView, WeekScheduleResponse } from "@/lib/types";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { useAppStore } from "@/stores/app-store";

interface FocusData {
  focus: FocusCurrentView | null;
  week: WeekScheduleResponse | null;
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

export function FocusView() {
  const { session } = useSessionBootstrap();
  const showNotice = useAppStore((state) => state.showNotice);
  const [data, setData] = useState<FocusData>({
    focus: null,
    week: null,
  });
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  async function loadFocusPage() {
    if (!session?.authenticated) {
      return;
    }

    try {
      setStatus("loading");
      const [focus, week] = await Promise.all([
        api.getFocusCurrent(),
        api.getWeekSchedule(),
      ]);

      setData({
        focus: focus.data,
        week,
      });
      setStatus("ready");
      setError(null);
    } catch (loadError) {
      setStatus("error");
      setError(loadError instanceof Error ? loadError.message : "포커스 화면을 준비하지 못했습니다.");
    }
  }

  useEffect(() => {
    if (!session?.authenticated) {
      return;
    }

    void loadFocusPage();
  }, [session?.authenticated]);

  async function withFocusMutation(action: () => Promise<unknown>, title: string) {
    try {
      await action();
      showNotice({
        tone: "success",
        title,
      });
      await loadFocusPage();
    } catch (mutationError) {
      showNotice({
        tone: "error",
        title: "포커스 작업을 처리하지 못했습니다.",
        detail:
          mutationError instanceof Error
            ? mutationError.message
            : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  const fallbackBlock = getFallbackFocusBlock(data.week, session?.timezone);
  const nextScheduleBlocks = getNextScheduleBlocks(data.week, 2, session?.timezone);
  const currentItem = data.focus?.currentItem ?? null;
  const primaryTitle = focusTitle(data.focus, fallbackBlock?.activity ?? null);

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
      void withFocusMutation(
        () => api.deleteFocusItem(currentItem.type, currentItem.id),
        `현재 ${itemLabel}을 삭제했습니다.`,
      ),
    );
  }

  return (
    <AppShell
      immersive
      eyebrow="집중 모드"
      title="집중 모드"
      description="지금 할 일과 now만 남기는 실행 전용 화면입니다."
    >
      <section className="focus-mode-stage">
        {!data.week && !data.focus && status === "loading" ? (
          <section className="surface-card empty-state">
            <strong>집중 모드를 준비하는 중입니다.</strong>
            <p>현재 항목과 다음 일정 정보를 불러오고 있습니다.</p>
          </section>
        ) : !data.week && !data.focus && status === "error" ? (
          <section className="surface-card empty-state">
            <strong>포커스 데이터를 불러오지 못했습니다.</strong>
            <p>{error ?? "백엔드 응답을 다시 확인해 주세요."}</p>
          </section>
        ) : (
          <div className="focus-mode-stack">
            <section className="focus-primary focus-mode-card">
              <Link
                className="ghost-btn link-btn focus-back-btn"
                href="/dashboard"
                aria-label="대시보드로 돌아가기"
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
                          void withFocusMutation(
                            () => api.completeFocusItem(currentItem.type, currentItem.id),
                            "현재 포커스 항목을 완료 처리했습니다.",
                          ),
                        )
                    : null
                }
                onPostponeCurrent={
                  currentItem && currentItem.type.toLowerCase() === "event"
                    ? () =>
                        startTransition(() =>
                          void withFocusMutation(
                            () => api.postponeFocusItem(currentItem.type, currentItem.id, "다음 블록과 충돌 가능성"),
                            "현재 항목을 미루고 재조율 대상으로 표시했습니다.",
                          ),
                        )
                    : null
                }
                onExtendCurrent={
                  currentItem && currentItem.type.toLowerCase() === "event"
                    ? () =>
                        startTransition(() =>
                          void withFocusMutation(
                            () => api.extendFocusItem(currentItem.type, currentItem.id, 15),
                            "15분 연장을 요청했습니다.",
                          ),
                        )
                    : null
                }
                onDeleteCurrent={currentItem ? handleDeleteCurrentItem : null}
                onStartRecommended={
                  data.focus?.recommendedTasks[0]
                    ? () =>
                        startTransition(() =>
                          void withFocusMutation(
                            () => api.startRecommendedTask(data.focus?.recommendedTasks[0].id ?? ""),
                            "추천 태스크를 시작했습니다.",
                          ),
                        )
                    : null
                }
              />
            </section>

            <FocusRailCard
              currentItem={currentItem}
              recommendedTasks={data.focus?.recommendedTasks ?? []}
              fallbackBlock={fallbackBlock}
              nextBlocks={nextScheduleBlocks}
            />
          </div>
        )}
      </section>
    </AppShell>
  );
}
