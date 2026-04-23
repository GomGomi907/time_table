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
    return "계산 중";
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
