import { SectionHeader } from "@/components/section-header";
import { SuggestionReviewCard } from "@/components/suggestion-review-card";
import { formatClockValue, formatRelativeMinutes, formatServiceCopy, formatUserMemo } from "@/lib/format";
import { DAY_FULL_LABELS, UpcomingScheduleBlock } from "@/lib/schedule";
import {
  FocusCurrentItem,
  RecommendedTask,
  RescheduleSuggestion,
  ScheduleBlock,
} from "@/lib/types";

interface FocusRailCardProps {
  currentItem: FocusCurrentItem | null;
  recommendedTasks: RecommendedTask[];
  fallbackBlock: ScheduleBlock | null;
  nextBlocks: UpcomingScheduleBlock[];
  pendingSuggestion?: RescheduleSuggestion | null;
  pendingConflictCount?: number;
  isPending?: boolean;
  onApplySuggestion?: (() => void) | null;
  onRejectSuggestion?: (() => void) | null;
}

function uniqueRecommendedTasks(tasks: RecommendedTask[], limit: number) {
  const seen = new Set<string>();
  const result: RecommendedTask[] = [];

  for (const task of tasks) {
    const title = formatServiceCopy(task.title).trim();
    if (seen.has(title)) {
      continue;
    }

    seen.add(title);
    result.push(task);
    if (result.length >= limit) {
      break;
    }
  }

  return result;
}

function visibleScheduleNote(note: string | null | undefined) {
  return formatUserMemo(note);
}

export function FocusRailCard({
  currentItem,
  recommendedTasks,
  fallbackBlock,
  nextBlocks,
  pendingSuggestion = null,
  pendingConflictCount = 0,
  isPending = false,
  onApplySuggestion = null,
  onRejectSuggestion = null,
}: FocusRailCardProps) {
  const visibleTasks = uniqueRecommendedTasks(recommendedTasks, 3);

  return (
    <article className="surface-card focus-rail-card focus-now-card">
      <div className="focus-rail-section">
        <SectionHeader eyebrow="지금" title="지금 할 일" />

        <ul className="compact-list">
          {currentItem ? (
            <>
              <li>
                <span>현재 할 일</span>
                <b data-user-content="true">{formatServiceCopy(currentItem.title)}</b>
              </li>
              <li>
                <span>목표</span>
                <b data-user-content={currentItem.goal ? "true" : undefined}>{currentItem.goal?.title ?? "없음"}</b>
              </li>
              <li>
                <span>우선순위</span>
                <b>{currentItem.priority}</b>
              </li>
            </>
          ) : visibleTasks.length ? (
            visibleTasks.map((task) => (
              <li key={task.id}>
                <span data-user-content="true">{formatServiceCopy(task.title)}</span>
                <b>{formatRelativeMinutes(task.estimatedMinutes)}</b>
              </li>
            ))
          ) : fallbackBlock ? (
            <>
              <li>
                <span>다음 일정</span>
                <b data-user-content="true">{formatServiceCopy(fallbackBlock.activity)}</b>
              </li>
              <li>
                <span>예정 시간</span>
                <b className="focus-time-range">
                  {formatClockValue(fallbackBlock.startTime)} - {formatClockValue(
                    fallbackBlock.endTime,
                  )}
                </b>
              </li>
            </>
          ) : (
            <li>
              <span>진행 중인 집중 항목 없음</span>
              <b>비어 있음</b>
            </li>
          )}
        </ul>
      </div>

      <div className="focus-rail-divider" />

      <div className="focus-rail-section">
        <SectionHeader eyebrow="다음 일정" title="곧 시작할 일정" />

        {nextBlocks.length ? (
          <div className="next-schedule-list">
            {nextBlocks.map((block) => {
              const note = visibleScheduleNote(block.note);
              return (
                <div className="next-card" key={`${block.dayOfWeek}-${block.id}`}>
                  <div className="next-card-head">
                    <span className="week-chip">
                      {DAY_FULL_LABELS[block.dayOfWeek] ?? block.dayOfWeek}
                  </span>
                  <span className="event-time">
                    {formatClockValue(block.startTime)} - {formatClockValue(block.endTime)}
                  </span>
                  </div>
                  <strong data-user-content="true">{formatServiceCopy(block.activity)}</strong>
                  {note ? <p className="next-card-note" data-user-content="true">{note}</p> : null}
                </div>
              );
            })}
          </div>
        ) : (
          <p className="warning-copy rail-empty-copy">다음 일정이 없습니다.</p>
        )}

        {pendingSuggestion && onApplySuggestion && onRejectSuggestion ? (
          <SuggestionReviewCard
            className="suggestion-box"
            isPending={isPending}
            suggestion={pendingSuggestion}
            onApply={onApplySuggestion}
            onReject={onRejectSuggestion}
          />
        ) : null}
      </div>
    </article>
  );
}
