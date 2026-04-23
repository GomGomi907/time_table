import { SectionHeader } from "@/components/section-header";
import { formatClockValue, formatRelativeMinutes } from "@/lib/format";
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
  const visibleTasks = recommendedTasks.slice(0, 3);
  const canHandleSuggestion = Boolean(onApplySuggestion && onRejectSuggestion);

  return (
    <article className="surface-card focus-rail-card focus-now-card">
      <div className="focus-rail-section">
        <SectionHeader eyebrow="지금" title="실행 메모" />

        <ul className="compact-list">
          {currentItem ? (
            <>
              <li>
                <span>현재 항목</span>
                <b>{currentItem.title}</b>
              </li>
              <li>
                <span>목표 연결</span>
                <b>{currentItem.goal?.title ?? "없음"}</b>
              </li>
              <li>
                <span>우선순위</span>
                <b>{currentItem.priority}</b>
              </li>
            </>
          ) : visibleTasks.length ? (
            visibleTasks.map((task) => (
              <li key={task.id}>
                <span>{task.title}</span>
                <b>{formatRelativeMinutes(task.estimatedMinutes)}</b>
              </li>
            ))
          ) : fallbackBlock ? (
            <>
              <li>
                <span>시간표 기준 다음 블록</span>
                <b>{fallbackBlock.activity}</b>
              </li>
              <li>
                <span>예정 시간</span>
                <b>
                  {formatClockValue(fallbackBlock.startTime)} - {formatClockValue(
                    fallbackBlock.endTime,
                  )}
                </b>
              </li>
            </>
          ) : (
            <li>
              <span>활성 포커스 없음</span>
              <b>비어 있음</b>
            </li>
          )}
        </ul>
      </div>

      <div className="focus-rail-divider" />

      <div className="focus-rail-section">
        <SectionHeader eyebrow="다음" title="다음 일정" />

        {nextBlocks.length ? (
          <div className="next-schedule-list">
            {nextBlocks.map((block) => (
              <div className="next-card" key={`${block.dayOfWeek}-${block.id}`}>
                <div className="next-card-head">
                  <span className="week-chip">
                    {DAY_FULL_LABELS[block.dayOfWeek] ?? block.dayOfWeek}
                  </span>
                  <span className="event-time">
                    {formatClockValue(block.startTime)} - {formatClockValue(block.endTime)}
                  </span>
                </div>
                <strong>{block.activity}</strong>
                {block.note ? <p className="next-card-note">{block.note}</p> : null}
              </div>
            ))}
          </div>
        ) : (
          <p className="warning-copy rail-empty-copy">다음 일정이 없습니다.</p>
        )}

        {pendingSuggestion ? (
          <div className="suggestion-box">
            <strong>{pendingSuggestion.summary}</strong>
            <p className="warning-copy">{pendingSuggestion.explanation}</p>
            <p className="micro-copy">대기 중 충돌 {pendingConflictCount}건</p>
            {canHandleSuggestion ? (
              <div className="suggestion-actions">
                <button
                  className="ghost-btn"
                  disabled={isPending}
                  onClick={onRejectSuggestion ?? undefined}
                >
                  나중에
                </button>
                <button
                  className="solid-btn"
                  disabled={isPending}
                  onClick={onApplySuggestion ?? undefined}
                >
                  이 안으로 조정
                </button>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    </article>
  );
}
