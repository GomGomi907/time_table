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
    return "AI 제안";
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
