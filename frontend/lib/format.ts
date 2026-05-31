import type { RescheduleSuggestion } from "@/lib/types";

function buildTimeFormatter(timeZone?: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    hour: "2-digit",
    minute: "2-digit",
    timeZone,
  });
}

function buildDateTimeFormatter(timeZone?: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone,
  });
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

  return buildTimeFormatter(timeZone).format(new Date(value));
}

export function formatDateTime(
  value: string | null | undefined,
  timeZone?: string,
) {
  if (!value) {
    return "기록 없음";
  }

  return buildDateTimeFormatter(timeZone).format(new Date(value));
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

export function formatServiceCopy(value: string | null | undefined) {
  return (value ?? "")
    .replaceAll("취미/탐색 (AI/Technical)", "기술 탐색")
    .replaceAll("Mock Google 태스크", "오늘 할 일 정리")
    .replaceAll("Mock Google 할 일", "오늘 할 일 정리")
    .replaceAll("Mock Google 회의", "제품 리뷰 회의")
    .replaceAll("추천 태스크는", "추천 할 일은")
    .replaceAll("태스크", "할 일")
    .replaceAll("할 일는", "할 일은");
}


const TEST_OR_INTERNAL_MEMO_PATTERN =
  /(e2e|playwright|qa seed|service improvement|mock|dashboard briefing pending approval)/i;

const AI_GENERATED_MEMO_PATTERN =
  /(AI|인공지능|제안합니다|제안해요|권장합니다|추천합니다|추천해요|추천|근거|예상 영향|확인한 내용|기준으로 재배치|조정안|최적화|리스크|검증|판단|추론|전략|의도|사용자(?:의)?\s*패턴|사용자(?:의)?\s*선호|회복 시간을 지키도록|주말.*(?:비워|회복|제안)|비워두고.*회복|완충|버퍼|컨디션 리셋)/i;

export function formatUserMemo(value: string | null | undefined) {
  const normalized = formatServiceCopy(value).trim();
  if (!normalized) {
    return null;
  }

  if (
    TEST_OR_INTERNAL_MEMO_PATTERN.test(normalized) ||
    INTERNAL_AI_METADATA_PATTERN.test(normalized) ||
    AI_GENERATED_MEMO_PATTERN.test(normalized)
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

  return AI_ACTION_LABELS[value.toLowerCase()] ?? value.replaceAll("_", " ");
}

export function formatAiPreviewDetail(value: string | null | undefined) {
  if (!value) {
    return null;
  }

  return Object.entries(DAY_LABELS).reduce(
    (detail, [day, label]) => detail.replaceAll(day, label),
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
      title: "지금은 적용할 수 없습니다.",
      detail: toSafeSuggestionText(message || suggestion.explanation, "잠시 후 다시 시도해 주세요."),
      guidance: "잠시 후 다시 시도하세요.",
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
