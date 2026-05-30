import { getSuggestionDisplayState } from "@/lib/format";
import type { RescheduleSuggestion } from "@/lib/types";

interface SuggestionReviewCardProps {
  suggestion: RescheduleSuggestion;
  isPending: boolean;
  className: string;
  onApply: () => void;
  onReject: () => void;
  kicker?: string;
  titleElement?: "h2" | "strong";
}

export function SuggestionReviewCard({
  suggestion,
  isPending,
  className,
  onApply,
  onReject,
  kicker = "변경 요청",
  titleElement = "strong",
}: SuggestionReviewCardProps) {
  const display = getSuggestionDisplayState(suggestion);
  const title =
    titleElement === "h2"
      ? <h2>{display.title}</h2>
      : <strong>{display.title}</strong>;

  return (
    <div className={`${className} ${display.kind}`}>
      <p className="panel-kicker">{kicker}</p>
      <div className="suggestion-diff-head">
        {title}
        <p className="section-header-note">{display.detail}</p>
        {display.guidance ? <p className="micro-copy suggestion-guidance">{display.guidance}</p> : null}
      </div>
      <div className="suggestion-actions approval-actions">
        <button className="ghost-btn" disabled={isPending} onClick={onReject} type="button">
          보류
        </button>
        <button className="solid-btn" disabled={isPending || !display.canApply} onClick={onApply} type="button">
          {display.applyLabel}
        </button>
      </div>
    </div>
  );
}
