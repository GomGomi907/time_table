import {
  formatAiActionLabel,
  formatAiPreviewDetail,
  formatServiceCopy,
  getSuggestionDisplayState,
  getSuggestionResultDetail,
} from "@/lib/format";
import type { RescheduleSuggestion } from "@/lib/types";

interface SuggestionReviewCardProps {
  suggestion: RescheduleSuggestion;
  isPending: boolean;
  className: string;
  onApply: () => void;
  onReject: () => void;
  kicker?: string;
  titleElement?: "h2" | "strong";
  readOnly?: boolean;
}

export function SuggestionReviewCard({
  suggestion,
  isPending,
  className,
  onApply,
  onReject,
  kicker = "변경 요청",
  titleElement = "strong",
  readOnly = false,
}: SuggestionReviewCardProps) {
  const display = getSuggestionDisplayState(suggestion);
  const executablePreviewItems = (suggestion.previewItems ?? []).filter((item) => item.executable);
  const previewItems = executablePreviewItems.slice(0, 3);
  const hiddenPreviewCount = Math.max(executablePreviewItems.length - previewItems.length, 0);
  const resultDetail = getSuggestionResultDetail(suggestion);
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
      {previewItems.length ? (
        <ul className="suggestion-preview-list" aria-label="적용될 변경">
          {previewItems.map((item, index) => {
            const detail = formatAiPreviewDetail(item.detail);
            return (
              <li className="suggestion-preview-item" key={`${item.actionType}-${item.targetId ?? index}-${index}`}>
                <span>{formatAiActionLabel(item.actionType)}</span>
                <strong>{formatServiceCopy(item.title)}</strong>
                {detail ? <p>{detail}</p> : null}
              </li>
            );
          })}
          {hiddenPreviewCount ? <li className="suggestion-preview-more">외 {hiddenPreviewCount}개 변경</li> : null}
        </ul>
      ) : null}
      {readOnly ? (
        <p className="suggestion-status-note">{resultDetail ?? suggestion.statusLabel}</p>
      ) : (
        <div className="suggestion-actions approval-actions">
          <button className="ghost-btn" disabled={isPending} onClick={onReject} type="button">
            보류
          </button>
          <button className="solid-btn" disabled={isPending || !display.canApply} onClick={onApply} type="button">
            {display.applyLabel}
          </button>
        </div>
      )}
    </div>
  );
}
