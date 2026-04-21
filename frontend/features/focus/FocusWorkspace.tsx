"use client";

import { Clock3, Loader2, PauseCircle, PlayCircle, RefreshCw } from "lucide-react";
import { useMemo } from "react";
import { PageIntro } from "@/components/app/PageIntro";
import { useFocusResource } from "./useFocusResource";

const FOCUS_MESSAGES: Record<string, string> = {
  NO_ACTIVE_ITEM: "지금 즉시 실행할 대상이 없습니다.",
  UPCOMING_EVENT_READY: "곧 시작할 일정이 준비 대기 중입니다.",
  ACTIVE_EVENT: "현재 일정에 집중하는 실행 상태입니다.",
  ACTIVE_RECOMMENDED_TASK: "추천 태스크를 실행하는 상태입니다.",
  AWAITING_END_CONFIRMATION: "예정 종료 시각이 지나 완료 여부 확인이 필요합니다.",
  POSTPONE_REQUESTED: "미루기 요청이 진행 중입니다.",
  RESCHEDULE_PENDING: "재조율이 필요하거나 충돌을 해결해야 합니다.",
  AUTO_RESCHEDULED: "자동 조율이 반영된 뒤의 상태입니다.",
  COMPLETED: "방금 집중 대상을 마쳤습니다.",
  INTERRUPTED: "집중 흐름이 중단된 상태입니다.",
};

const formatDateTime = (value?: string | null) => {
  if (!value) {
    return "미정";
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return "확인 필요";
  }
  return new Intl.DateTimeFormat("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(parsed);
};

export const FocusWorkspace = () => {
  const focus = useFocusResource();
  const data = focus.data;

  const primaryAction = useMemo(() => {
    if (!data?.currentItem) {
      return null;
    }

    const currentItem = data.currentItem;
    return async () =>
      focus.completeCurrent({
        itemType: currentItem.type,
        itemId: currentItem.id,
        completedAt: new Date().toISOString(),
        completionType: "on_time",
      });
  }, [data, focus]);

  return (
    <div className="space-y-6">
      <PageIntro
        eyebrow="Execution Mode"
        title="집중 화면"
        description="현재 해야 할 일 하나와 바로 이어질 전환 액션만 남긴 실행 전용 화면입니다."
        actions={
          <button
            type="button"
            onClick={() => void focus.refresh()}
            className="btn-secondary"
          >
            <RefreshCw className={`h-4 w-4 ${focus.isLoading ? "animate-spin" : ""}`} />
            새로고침
          </button>
        }
      />

      {focus.error ? (
        <section className="surface-card-muted p-4">
          <p className="text-sm font-semibold text-[var(--foreground)]">{focus.error}</p>
        </section>
      ) : null}

      <section className="surface-card p-6 sm:p-8">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
          <div className="space-y-3">
            <p className="metric-label">현재 상태</p>
            <h2 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">
              {data?.currentItem?.title ?? "집중 대상 없음"}
            </h2>
            <p className="max-w-2xl text-sm leading-6 text-[var(--foreground-muted)]">
              {data ? FOCUS_MESSAGES[data.focusState] ?? "현재 상태를 분석 중입니다." : "실행 상태를 불러오는 중입니다."}
            </p>
          </div>

          <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-muted)] px-4 py-3">
            <div className="flex items-center gap-2 text-sm font-semibold text-[var(--foreground)]">
              <Clock3 className="h-4 w-4 text-[var(--accent)]" />
              남은 시간 {data?.remainingMinutes ?? 0}분
            </div>
          </div>
        </div>

        <div className="mt-6 grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
          <article className="rounded-[1.6rem] border border-[var(--border)] bg-[var(--surface-raised)] p-5">
            {focus.isLoading && !data ? (
              <div className="flex items-center gap-3 text-sm text-[var(--foreground-muted)]">
                <Loader2 className="h-4 w-4 animate-spin" />
                집중 상태를 불러오는 중입니다.
              </div>
            ) : (
              <div className="space-y-4">
                <div className="flex flex-wrap gap-2">
                  <span className="status-badge">{data?.focusState ?? "NO_ACTIVE_ITEM"}</span>
                  {data?.currentItem?.goal ? (
                    <span className="status-badge">{data.currentItem.goal.title}</span>
                  ) : null}
                </div>
                <div>
                  <p className="metric-label">현재 대상</p>
                  <p className="mt-2 text-xl font-bold text-[var(--foreground)]">
                    {data?.currentItem?.title ?? "지금 실행할 대상이 없습니다."}
                  </p>
                  <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
                    {data?.currentItem
                      ? `${formatDateTime(data.currentItem.startAt)} - ${formatDateTime(data.currentItem.endAt)}`
                      : "추천 태스크를 시작하거나 다음 일정 준비를 먼저 해 두세요."}
                  </p>
                </div>
                <div className="flex flex-wrap gap-3">
                  <button
                    type="button"
                    className="btn-primary"
                    onClick={() => {
                      if (primaryAction) {
                        void primaryAction();
                      }
                    }}
                    disabled={!data?.currentItem || focus.isMutating}
                  >
                    <PlayCircle className="h-4 w-4" />
                    완료 처리
                  </button>
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={!data?.currentItem || focus.isMutating}
                    onClick={() => {
                      if (!data?.currentItem) {
                        return;
                      }
                      void focus.postponeCurrent({
                        itemType: data.currentItem.type,
                        itemId: data.currentItem.id,
                        reason: "사용자 수동 미루기",
                        requestAiReschedule: true,
                      });
                    }}
                  >
                    <PauseCircle className="h-4 w-4" />
                    미루기
                  </button>
                </div>
              </div>
            )}
          </article>

          <article className="rounded-[1.6rem] border border-[var(--border)] bg-[var(--surface-raised)] p-5">
            <p className="metric-label">다음 일정</p>
            <p className="mt-3 text-lg font-bold text-[var(--foreground)]">
              {data?.nextItem?.title ?? "예정 없음"}
            </p>
            <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
              {data?.nextItem
                ? formatDateTime(data.nextItem.startAt)
                : "후속 일정이 없으면 추천 태스크나 회고 시간으로 전환할 수 있습니다."}
            </p>
          </article>
        </div>
      </section>

      <section className="surface-card p-6 sm:p-8">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="metric-label">추천 태스크</p>
            <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
              빈 시간 활용
            </h2>
          </div>
        </div>

        <div className="mt-5 space-y-3">
          {data?.recommendedTasks.length ? (
            data.recommendedTasks.map((task) => (
              <article
                key={task.id}
                className="rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4"
              >
                <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                  <div>
                    <p className="text-sm font-semibold text-[var(--foreground)]">{task.title}</p>
                    <p className="mt-2 text-sm text-[var(--foreground-muted)]">
                      {task.estimatedMinutes
                        ? `예상 ${task.estimatedMinutes}분`
                        : "예상 소요 미설정"}
                    </p>
                  </div>
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={focus.isMutating}
                    onClick={() => void focus.startRecommendedTask({ taskId: task.id })}
                  >
                    바로 시작
                  </button>
                </div>
              </article>
            ))
          ) : (
            <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
              지금 추천할 태스크가 없습니다.
            </div>
          )}
        </div>
      </section>
    </div>
  );
};
