import type { RescheduleSuggestion } from "@/lib/types";

function buildTimeFormatter(timeZone?: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    hour: "2-digit",
    minute: "2-digit",
    timeZone: normalizeTimeZone(timeZone),
  });
}

function buildDateTimeFormatter(timeZone?: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: normalizeTimeZone(timeZone),
  });
}

function normalizeTimeZone(timeZone?: string) {
  const normalized = timeZone?.trim();
  if (!normalized) {
    return "UTC";
  }
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: normalized }).format(new Date(0));
    return normalized;
  } catch {
    return "UTC";
  }
}

export function formatClockValue(
  value: string | null | undefined,
  timeZone?: string,
) {
  if (!value) {
    return "--:--";
  }

  if (/^\d{2}:\d{2}/.test(value)) {
    return value.slice(0, 5);
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "--:--";
  }
  return buildTimeFormatter(timeZone).format(date);
}

export function formatDateTime(
  value: string | null | undefined,
  timeZone?: string,
) {
  if (!value) {
    return "기록 없음";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "기록 없음";
  }
  return buildDateTimeFormatter(timeZone).format(date);
}

export function formatRelativeMinutes(minutes: number | null | undefined) {
  if (minutes == null) {
    return "확인 중";
  }

  if (minutes < 60) {
    return `${minutes}분`;
  }

  const hours = Math.floor(minutes / 60);
  const remain = minutes % 60;
  return remain === 0 ? `${hours}시간` : `${hours}시간 ${remain}분`;
}

export function formatPercent(value: number) {
  return `${Math.max(0, Math.min(100, Math.round(value)))}%`;
}

const SERVICE_COPY_EXACT_LABELS = new Map<string, string>([
  ["취미/탐색 (AI/Technical)", "기술 탐색"],
  ["Mock Google 태스크", "오늘 할 일 정리"],
  ["Mock Google 할 일", "오늘 할 일 정리"],
  ["Mock Google 회의", "제품 리뷰 회의"],
]);

const LEGACY_TASK_PARTICLE: Record<string, string> = {
  "은": "은",
  "는": "은",
  "이": "이",
  "가": "이",
  "을": "을",
  "를": "을",
};

function normalizeLegacyProductTerms(value: string) {
  return value.replace(/태스크([은는이가을를])?/g, (_match, particle: string | undefined) => (
    `할 일${particle ? LEGACY_TASK_PARTICLE[particle] : ""}`
  ));
}

export function formatServiceCopy(value: string | null | undefined) {
  const normalized = (value ?? "").trim();
  const exactLabel = SERVICE_COPY_EXACT_LABELS.get(normalized);
  return exactLabel ?? normalizeLegacyProductTerms(normalized);
}


const TEST_OR_INTERNAL_MEMO_PATTERN =
  /(e2e|playwright|qa seed|service improvement|mock|dashboard briefing pending approval)/i;

export function formatUserFacingAiCopy(value: string | null | undefined, fallback = "") {
  const normalized = sanitizeInternalAiCopy(formatServiceCopy(value));
  if (!normalized) {
    return fallback;
  }
  return normalized;
}

export function formatUserMemo(value: string | null | undefined) {
  const normalized = formatUserFacingAiCopy(value);
  if (!normalized || TEST_OR_INTERNAL_MEMO_PATTERN.test(normalized)) {
    return null;
  }

  return normalized;
}

const AI_ACTION_LABELS: Record<string, string> = {
  create_event: "새 일정",
  move_event: "일정 이동",
  update_event: "일정 수정",
  delete_event: "일정 삭제",
  request_reschedule: "조정 요청",
  recommend_task: "추천 할 일",
  explain_only: "안내",
  run_sync: "동기화",
  propose_priority: "우선순위",
  change_settings: "설정 변경",
  update_goal_progress: "목표 업데이트",
  revert_suggestion: "되돌리기",
};

const DAY_LABELS: Record<string, string> = {
  MONDAY: "월요일",
  TUESDAY: "화요일",
  WEDNESDAY: "수요일",
  THURSDAY: "목요일",
  FRIDAY: "금요일",
  SATURDAY: "토요일",
  SUNDAY: "일요일",
};

export function formatAiActionLabel(value: string | null | undefined) {
  if (!value) {
    return "변경";
  }

  return AI_ACTION_LABELS[value.toLowerCase()] ?? value.split("_").join(" ");
}

export function formatAiPreviewDetail(value: string | null | undefined) {
  if (!value) {
    return null;
  }

  const detail = Object.entries(DAY_LABELS).reduce(
    (detail, [day, label]) => detail.split(day).join(label),
    formatServiceCopy(value),
  );
  return formatUserFacingAiCopy(detail) || null;
}

export type SuggestionDisplayKind = "executable" | "clarification" | "provider_unavailable" | "non_executable";

export interface SuggestionDisplayState {
  kind: SuggestionDisplayKind;
  title: string;
  detail: string;
  guidance: string | null;
  canApply: boolean;
  applyLabel: string;
}

const INTERNAL_AI_SENTINEL_PATTERN = /INTERNAL_REASON_SHOULD_NOT_RENDER/i;
const INTERNAL_AI_METADATA_TERMS =
  "confidence|stage|draft|canonical|commandBatch|command|providerMetadata|provider_metadata|payload|requestKind|resolutionType|matchEvidence|validationTrace|validation|repairAttempt|chainOfThought|reasoning|missingFields|ambiguousFields|schedule block";
const INTERNAL_AI_METADATA_LABEL_PATTERN = new RegExp(
  `\\b(?:${INTERNAL_AI_METADATA_TERMS})\\b\\s*[:=]\\s*[^,.;\\n]+[,.;\\n]?`,
  "gi",
);
const INTERNAL_AI_METADATA_TOKEN_PATTERN = new RegExp(`\\b(?:${INTERNAL_AI_METADATA_TERMS})\\b`, "gi");
const INTERNAL_AI_COPY_MESSAGE_REPLACEMENTS: Array<[RegExp, string]> = [
  [/AI\s*재조율\s*응답\s*스키마[^.?!。]*(?:[.?!。]|$)/g, "요청을 준비하지 못했습니다."],
  [/Gemini\s+provider\s+quota\s+exhausted[^\n]*/gi, "사용량 한도나 결제 크레딧이 부족해 요청을 처리하지 못했습니다."],
  [/Gemini\s+provider\s+request\s+failed[^\n]*/gi, "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."],
  [/(?:suggestion\s+)?payload[^\n.?!。]*(?:읽|저장)[^.?!。]*(?:[.?!。]|$)/gi, "제안 내용을 처리하지 못했습니다."],
  [/execution\s+snapshot[^\n.?!。]*(?:읽|저장)[^.?!。]*(?:[.?!。]|$)/gi, "적용 기록을 처리하지 못했습니다."],
  [/AI\s*repair\s*(?:응답|요청)[^.?!。]*(?:[.?!。]|$)/gi, "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."],
];
const INTERNAL_AI_COPY_REPLACEMENTS: Array<[RegExp, string]> = [
  [/AI\s*초안/g, "확인할 변경"],
  [/후보\s*명령/g, "확인할 변경"],
  [/초안/g, "확인할 변경"],
  [/명령/g, "변경"],
  [/제공자/g, "AI"],
  [/검증/g, "확인"],
];

export function sanitizeInternalAiCopy(value: string | null | undefined) {
  let normalized = (value ?? "").trim();
  if (!normalized || INTERNAL_AI_SENTINEL_PATTERN.test(normalized)) {
    return "";
  }

  for (const [pattern, replacement] of INTERNAL_AI_COPY_MESSAGE_REPLACEMENTS) {
    normalized = normalized.replace(pattern, replacement);
  }

  const hadMetadata = INTERNAL_AI_METADATA_LABEL_PATTERN.test(normalized)
    || INTERNAL_AI_METADATA_TOKEN_PATTERN.test(normalized);
  INTERNAL_AI_METADATA_LABEL_PATTERN.lastIndex = 0;
  INTERNAL_AI_METADATA_TOKEN_PATTERN.lastIndex = 0;

  normalized = normalized
    .replace(INTERNAL_AI_METADATA_LABEL_PATTERN, " ")
    .replace(INTERNAL_AI_METADATA_TOKEN_PATTERN, " ");
  for (const [pattern, replacement] of INTERNAL_AI_COPY_REPLACEMENTS) {
    normalized = normalized.replace(pattern, replacement);
  }
  normalized = normalized
    .replace(/\s*([,.;:])\s*/g, "$1 ")
    .replace(/\s{2,}/g, " ")
    .trim();

  if (hadMetadata && !/[가-힣]/.test(normalized)) {
    return "";
  }
  return normalized;
}

function getFirstCommandPayload(suggestion: RescheduleSuggestion) {
  const payload = suggestion.commandBatch?.commands?.[0]?.payload;
  return payload && typeof payload === "object" ? payload as Record<string, unknown> : null;
}

function getStringField(record: Record<string, unknown> | null, key: string) {
  const value = record?.[key];
  return typeof value === "string" ? value.trim() : "";
}

function toSafeSuggestionText(value: string | null | undefined, fallback: string) {
  return formatUserFacingAiCopy(value, fallback);
}

export function getSuggestionResolutionType(suggestion: RescheduleSuggestion) {
  const resolutionType = getStringField(getFirstCommandPayload(suggestion), "resolutionType");
  return resolutionType || null;
}

export function getSuggestionDisplayState(suggestion: RescheduleSuggestion): SuggestionDisplayState {
  const decisionPackage = suggestion.decisionPackage;
  const trustLevel = decisionPackage?.trustLevel;
  const understanding = decisionPackage?.understanding;

  if (trustLevel === "provider_unavailable") {
    return {
      kind: "provider_unavailable",
      title: "지금은 요청을 처리하지 못했습니다.",
      detail: toSafeSuggestionText(decisionPackage?.confirmationReason || understanding?.explanation || suggestion.explanation, "잠시 후 다시 시도해 주세요."),
      guidance: "입력한 요청은 남아 있습니다. 잠시 후 다시 보내 주세요.",
      canApply: false,
      applyLabel: "다시 보내기",
    };
  }

  if (trustLevel === "clarification_required" || decisionPackage?.userEffort?.needsClarification) {
    return {
      kind: "clarification",
      title: toSafeSuggestionText(understanding?.summary || suggestion.summary, "확인이 필요합니다."),
      detail: toSafeSuggestionText(
        decisionPackage?.clarificationQuestion || decisionPackage?.userEffort?.question || understanding?.explanation || suggestion.explanation,
        "바꾸고 싶은 내용을 한 문장으로 더 알려주세요.",
      ),
      guidance: null,
      canApply: false,
      applyLabel: "추가 정보 필요",
    };
  }

  if (suggestion.executable) {
    return {
      kind: "executable",
      title: "확인할 변경",
      detail: toSafeSuggestionText(decisionPackage?.confirmationReason || understanding?.explanation, "시간과 내용을 확인해 주세요."),
      guidance: decisionPackage?.riskLevel === "high" ? "영향이 큰 변경입니다. 반영 전에 내용을 확인해 주세요." : null,
      canApply: true,
      applyLabel: trustLevel === "review_required" ? "확인 후 반영" : "반영하기",
    };
  }

  if (trustLevel === "review_required" || decisionPackage?.requiresConfirmation) {
    return {
      kind: "clarification",
      title: toSafeSuggestionText(understanding?.summary || suggestion.summary, "확인이 필요합니다."),
      detail: toSafeSuggestionText(decisionPackage?.confirmationReason || understanding?.explanation || suggestion.explanation, "반영 전에 내용 확인이 필요합니다."),
      guidance: "반영 전에 확인할 내용을 정리했습니다.",
      canApply: false,
      applyLabel: "확인 필요",
    };
  }

  const payload = getFirstCommandPayload(suggestion);
  const resolutionType = getSuggestionResolutionType(suggestion);

  if (resolutionType === "clarification_required") {
    const question = getStringField(payload, "clarificationQuestion");
    return {
      kind: "clarification",
      title: toSafeSuggestionText(suggestion.summary, "확인이 필요합니다."),
      detail: toSafeSuggestionText(
        question || suggestion.explanation,
        "바꾸고 싶은 내용을 한 문장으로 더 알려주세요.",
      ),
      guidance: null,
      canApply: false,
      applyLabel: "추가 정보 필요",
    };
  }

  if (resolutionType === "provider_unavailable") {
    const message = getStringField(payload, "message");
    return {
      kind: "provider_unavailable",
      title: "지금은 요청을 처리하지 못했습니다.",
      detail: toSafeSuggestionText(message || suggestion.explanation, "잠시 후 다시 시도해 주세요."),
      guidance: "입력한 요청은 남아 있습니다. 잠시 후 다시 보내 주세요.",
      canApply: false,
      applyLabel: "다시 보내기",
    };
  }

  return {
    kind: "non_executable",
    title: toSafeSuggestionText(understanding?.summary || suggestion.summary, "반영할 내용이 없습니다."),
    detail: toSafeSuggestionText(understanding?.explanation || suggestion.explanation, "원하는 날짜나 시간을 조금 더 알려주세요."),
    guidance: null,
    canApply: false,
    applyLabel: "정보 확인",
  };
}

export function getSuggestionResultDetail(suggestion: RescheduleSuggestion | null | undefined) {
  if (!suggestion) {
    return undefined;
  }

  const detail = suggestion.executionSummary?.detail || suggestion.statusDetail;
  const normalized = toSafeSuggestionText(detail, "");
  return normalized || undefined;
}

export function getSuggestionNoticeDetail(response: unknown) {
  if (!response || typeof response !== "object" || !("data" in response)) {
    return undefined;
  }

  const data = (response as { data?: unknown }).data;
  if (!data || typeof data !== "object" || !("statusDetail" in data)) {
    return undefined;
  }

  return getSuggestionResultDetail(data as RescheduleSuggestion);
}
