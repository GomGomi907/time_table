import {
  formatAiActionLabel,
  formatAiPreviewDetail,
  formatServiceCopy,
  getSuggestionDisplayState,
  getSuggestionResultDetail,
} from "@/lib/format";
import type { AiDecisionDisplaySection, RescheduleSuggestion } from "@/lib/types";


function getDecisionSections(suggestion: RescheduleSuggestion): AiDecisionDisplaySection[] {
  const directSections = suggestion.decisionPackage?.displaySections ?? [];
  if (directSections.length) {
    return directSections.filter((section) => section.body || (section.items ?? []).length);
  }

  const legacySections = suggestion.decisionPackage?.trustUxSections;
  if (!legacySections) {
    return [];
  }

  return Object.entries(legacySections).map(([label, body], index) => ({
    key: `legacy-${index}`,
    label,
    body,
    items: [],
    severity: "neutral",
  }));
}

function DecisionSectionList({ sections }: { sections: AiDecisionDisplaySection[] }) {
  if (!sections.length) {
    return null;
  }

  return (
    <dl className="suggestion-decision-sections" aria-label="AI 판단 근거">
      {sections.map((section) => (
        <div className={`suggestion-decision-section ${section.severity}`} key={section.key || section.label}>
          <dt>{section.label}</dt>
          {section.body ? <dd>{formatServiceCopy(section.body)}</dd> : null}
          {(section.items ?? []).length ? (
            <ul>
              {(section.items ?? []).slice(0, 4).map((item, index) => (
                <li key={`${section.key}-${index}`}>{formatServiceCopy(item)}</li>
              ))}
              {(section.items ?? []).length > 4 ? <li>외 {(section.items ?? []).length - 4}개</li> : null}
            </ul>
          ) : null}
        </div>
      ))}
    </dl>
  );
}
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
  const decisionSections = getDecisionSections(suggestion);
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
      <DecisionSectionList sections={decisionSections} />
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
            이 제안 사용 안 함
          </button>
          <button className="solid-btn" disabled={isPending || !display.canApply} onClick={onApply} type="button">
            {display.applyLabel}
          </button>
        </div>
      )}
    </div>
  );
}
