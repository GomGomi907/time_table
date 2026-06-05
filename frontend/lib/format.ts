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

export function formatUserMemo(value: string | null | undefined) {
  const normalized = formatServiceCopy(value).trim();
  if (!normalized) {
    return null;
  }

  if (
    TEST_OR_INTERNAL_MEMO_PATTERN.test(normalized) ||
    INTERNAL_AI_METADATA_PATTERN.test(normalized)
  ) {
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
    return "제안";
  }

  return AI_ACTION_LABELS[value.toLowerCase()] ?? value.split("_").join(" ");
}

export function formatAiPreviewDetail(value: string | null | undefined) {
  if (!value) {
    return null;
  }

  return Object.entries(DAY_LABELS).reduce(
    (detail, [day, label]) => detail.split(day).join(label),
    formatServiceCopy(value),
  );
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

const INTERNAL_AI_METADATA_PATTERN =
  /\b(confidence|stage|matchEvidence|validationTrace|repairAttempt|chainOfThought|reasoning|reason|missingFields|ambiguousFields)\b/i;

function getFirstCommandPayload(suggestion: RescheduleSuggestion) {
  const payload = suggestion.commandBatch?.commands?.[0]?.payload;
  return payload && typeof payload === "object" ? payload as Record<string, unknown> : null;
}

function getStringField(record: Record<string, unknown> | null, key: string) {
  const value = record?.[key];
  return typeof value === "string" ? value.trim() : "";
}

function toSafeSuggestionText(value: string | null | undefined, fallback: string) {
  const normalized = formatServiceCopy(value).trim();
  if (!normalized || INTERNAL_AI_METADATA_PATTERN.test(normalized)) {
    return fallback;
  }
  return normalized;
}

export function getSuggestionResolutionType(suggestion: RescheduleSuggestion) {
  const resolutionType = getStringField(getFirstCommandPayload(suggestion), "resolutionType");
  return resolutionType || null;
}

export function getSuggestionDisplayState(suggestion: RescheduleSuggestion): SuggestionDisplayState {
  if (suggestion.executable) {
    return {
      kind: "executable",
      title: "변경 제안",
      detail: "변경 시간을 확인하고 적용하세요.",
      guidance: null,
      canApply: true,
      applyLabel: "적용",
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
      applyLabel: "적용할 변경 없음",
    };
  }

  if (resolutionType === "provider_unavailable") {
    const message = getStringField(payload, "message");
    return {
      kind: "provider_unavailable",
      title: "AI 요청을 처리하지 못했습니다.",
      detail: toSafeSuggestionText(message || suggestion.explanation, "잠시 후 다시 시도해 주세요."),
      guidance: "요청 내용은 보존됩니다. 원인을 확인한 뒤 다시 요청하세요.",
      canApply: false,
      applyLabel: "다시 요청 필요",
    };
  }

  return {
    kind: "non_executable",
    title: toSafeSuggestionText(suggestion.summary, "적용할 변경이 없습니다."),
    detail: toSafeSuggestionText(suggestion.explanation, "요청을 더 구체적으로 다시 보내주세요."),
    guidance: "필요하면 일정을 다시 작성해 보내세요.",
    canApply: false,
    applyLabel: "적용할 변경 없음",
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
