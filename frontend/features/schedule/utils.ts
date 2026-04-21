import type {
  DailyScheduleResponse,
  DayKey,
  ScheduleBlockResponse,
  ScheduleCategory,
  ScheduleSourceType,
  WeekScheduleResponse,
} from "@/shared/api/types";

export const DAY_ORDER: DayKey[] = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
];

export const DAY_LABELS: Record<DayKey, string> = {
  Monday: "월",
  Tuesday: "화",
  Wednesday: "수",
  Thursday: "목",
  Friday: "금",
  Saturday: "토",
  Sunday: "일",
};

export const DAY_FULL_LABELS: Record<DayKey, string> = {
  Monday: "월요일",
  Tuesday: "화요일",
  Wednesday: "수요일",
  Thursday: "목요일",
  Friday: "금요일",
  Saturday: "토요일",
  Sunday: "일요일",
};

export const CATEGORY_LABELS: Record<ScheduleCategory, string> = {
  WORK: "업무",
  LIFE: "생활",
  TRANSIT: "이동",
  GROWTH: "성장",
  HOBBY: "취미",
  SLEEP: "수면",
  ADMIN: "관리",
};

export const SOURCE_LABELS: Record<ScheduleSourceType, string> = {
  DEFAULT_ROUTINE: "기본 루틴",
  GEMMA_IMPORT: "Gemma4 반영",
  MANUAL: "직접 편집",
};

export const CATEGORY_TONES: Record<
  ScheduleCategory,
  { line: string; badgeBackground: string; badgeText: string }
> = {
  WORK: {
    line: "#2f63db",
    badgeBackground: "rgba(47, 99, 219, 0.12)",
    badgeText: "#234ca9",
  },
  LIFE: {
    line: "#596173",
    badgeBackground: "rgba(89, 97, 115, 0.12)",
    badgeText: "#4d5666",
  },
  TRANSIT: {
    line: "#867460",
    badgeBackground: "rgba(134, 116, 96, 0.12)",
    badgeText: "#725f49",
  },
  GROWTH: {
    line: "#1880c7",
    badgeBackground: "rgba(24, 128, 199, 0.12)",
    badgeText: "#0f659f",
  },
  HOBBY: {
    line: "#785fd2",
    badgeBackground: "rgba(120, 95, 210, 0.12)",
    badgeText: "#654fb6",
  },
  SLEEP: {
    line: "#12806a",
    badgeBackground: "rgba(18, 128, 106, 0.12)",
    badgeText: "#0b6958",
  },
  ADMIN: {
    line: "#ba7c1f",
    badgeBackground: "rgba(186, 124, 31, 0.12)",
    badgeText: "#8d5a18",
  },
};

export const createEmptyDay = (dayOfWeek: DayKey): DailyScheduleResponse => ({
  dayOfWeek,
  blocks: [],
});

export const findDaySchedule = (
  response: WeekScheduleResponse | null,
  dayOfWeek: DayKey
): DailyScheduleResponse => {
  if (!response) {
    return createEmptyDay(dayOfWeek);
  }

  return (
    response.week.find((day) => day.dayOfWeek === dayOfWeek) ?? createEmptyDay(dayOfWeek)
  );
};

export const sortBlocks = (blocks: ScheduleBlockResponse[]) =>
  [...blocks].sort((left, right) => {
    const leftStart = toDisplayMinutes(left.startTime);
    const rightStart = toDisplayMinutes(right.startTime);
    if (leftStart !== rightStart) {
      return leftStart - rightStart;
    }
    return toDisplayMinutes(left.endTime) - toDisplayMinutes(right.endTime);
  });

export const toMinutes = (value: string) => {
  const [hour, minute] = value.split(":").map(Number);
  return hour * 60 + minute;
};

const toDisplayMinutes = (value: string) => {
  const minutes = toMinutes(value);
  return minutes < 300 ? minutes + 1440 : minutes;
};

export const getTodayDayKey = (now: Date = new Date()): DayKey => {
  const map: Record<number, DayKey> = {
    0: "Sunday",
    1: "Monday",
    2: "Tuesday",
    3: "Wednesday",
    4: "Thursday",
    5: "Friday",
    6: "Saturday",
  };

  return map[now.getDay()];
};

export const getCurrentBlock = (
  blocks: ScheduleBlockResponse[],
  now: Date = new Date()
) => {
  const currentMinutes = now.getHours() * 60 + now.getMinutes();
  return sortBlocks(blocks).find((block) => {
    const start = toMinutes(block.startTime);
    const end = toMinutes(block.endTime);

    if (end < start) {
      return currentMinutes >= start || currentMinutes < end;
    }

    return currentMinutes >= start && currentMinutes < end;
  });
};

export const getNextBlock = (
  blocks: ScheduleBlockResponse[],
  now: Date = new Date()
) => {
  const currentMinutes = now.getHours() * 60 + now.getMinutes();

  return sortBlocks(blocks).find((block) => {
    const start = toDisplayMinutes(block.startTime);
    const current = currentMinutes < 300 ? currentMinutes + 1440 : currentMinutes;
    return start > current;
  });
};

export const getProgressPercent = (
  block: ScheduleBlockResponse,
  now: Date = new Date()
) => {
  const start = toMinutes(block.startTime);
  let end = toMinutes(block.endTime);
  let current = now.getHours() * 60 + now.getMinutes();

  if (end < start) {
    end += 1440;
    if (current < start) {
      current += 1440;
    }
  }

  if (current <= start) {
    return 0;
  }

  if (current >= end) {
    return 100;
  }

  return Math.round(((current - start) / (end - start)) * 100);
};

export const getRemainingLabel = (
  block: ScheduleBlockResponse,
  now: Date = new Date()
) => {
  let end = toMinutes(block.endTime);
  let current = now.getHours() * 60 + now.getMinutes();
  const start = toMinutes(block.startTime);

  if (end < start) {
    end += 1440;
    if (current < start) {
      current += 1440;
    }
  }

  const remaining = Math.max(0, end - current);
  const hours = Math.floor(remaining / 60);
  const minutes = remaining % 60;

  if (hours > 0) {
    return `${hours}시간 ${minutes}분 남음`;
  }

  return `${minutes}분 남음`;
};

export const formatNowLabel = (now: Date = new Date()) =>
  new Intl.DateTimeFormat("ko-KR", {
    month: "long",
    day: "numeric",
    weekday: "long",
    hour: "2-digit",
    minute: "2-digit",
  }).format(now);

export const formatTimeRange = (startTime: string, endTime: string) =>
  `${startTime.slice(0, 5)} - ${endTime.slice(0, 5)}`;
