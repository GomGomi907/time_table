import { FocusCurrentItem } from "@/lib/types";

interface FocusActionBarProps {
  currentItem: FocusCurrentItem | null;
  hasRecommendedTask?: boolean;
  isPending?: boolean;
  onCompleteCurrent?: (() => void) | null;
  onPostponeCurrent?: (() => void) | null;
  onExtendCurrent?: (() => void) | null;
  onDeleteCurrent?: (() => void) | null;
  onStartRecommended?: (() => void) | null;
}

function getCurrentItemLabel(type: string | null | undefined) {
  return type?.toLowerCase() === "task" ? "할 일" : "일정";
}

export function FocusActionBar({
  currentItem,
  hasRecommendedTask = false,
  isPending = false,
  onCompleteCurrent = null,
  onPostponeCurrent = null,
  onExtendCurrent = null,
  onDeleteCurrent = null,
  onStartRecommended = null,
}: FocusActionBarProps) {
  if (currentItem) {
    const itemLabel = getCurrentItemLabel(currentItem.type);

    return (
      <div className="focus-action-stack">
        <div className="focus-actions">
          {onCompleteCurrent ? (
            <button className="solid-btn" disabled={isPending} onClick={onCompleteCurrent}>
              {itemLabel} 완료
            </button>
          ) : null}
          {onPostponeCurrent ? (
            <button className="ghost-btn" disabled={isPending} onClick={onPostponeCurrent}>
              미루기
            </button>
          ) : null}
          {onExtendCurrent ? (
            <button className="ghost-btn" disabled={isPending} onClick={onExtendCurrent}>
              15분 연장
            </button>
          ) : null}
        </div>
        {onDeleteCurrent ? (
          <div className="focus-actions secondary">
            <button
              className="ghost-btn danger-btn"
              disabled={isPending}
              onClick={onDeleteCurrent}
            >
              삭제
            </button>
          </div>
        ) : null}
      </div>
    );
  }

  if (hasRecommendedTask && onStartRecommended) {
    return (
      <div className="focus-actions">
        <button className="solid-btn" disabled={isPending} onClick={onStartRecommended}>
          할 일 시작
        </button>
      </div>
    );
  }

  return null;
}
