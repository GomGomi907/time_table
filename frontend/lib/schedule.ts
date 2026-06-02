import { DailyRoutine, ScheduleBlock, WeekScheduleResponse } from "@/lib/types";

export const DAY_ORDER = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
] as const;

export const DAY_LABELS: Record<string, string> = {
  Monday: "월",
  Tuesday: "화",
  Wednesday: "수",
  Thursday: "목",
  Friday: "금",
  Saturday: "토",
  Sunday: "일",
  MONDAY: "월",
  TUESDAY: "화",
  WEDNESDAY: "수",
  THURSDAY: "목",
  FRIDAY: "금",
  SATURDAY: "토",
  SUNDAY: "일",
};

export const DAY_FULL_LABELS: Record<string, string> = {
  Monday: "월요일",
  Tuesday: "화요일",
  Wednesday: "수요일",
  Thursday: "목요일",
  Friday: "금요일",
  Saturday: "토요일",
  Sunday: "일요일",
  MONDAY: "월요일",
  TUESDAY: "화요일",
  WEDNESDAY: "수요일",
  THURSDAY: "목요일",
  FRIDAY: "금요일",
  SATURDAY: "토요일",
  SUNDAY: "일요일",
};

export const CATEGORY_LABELS: Record<string, string> = {
  WORK: "집중 업무",
  GROWTH: "성장",
  LIFE: "생활",
  TRANSIT: "이동",
  HOBBY: "취미",
  SLEEP: "수면",
  ADMIN: "관리",
  HEALTH: "건강",
};

const LOW_SIGNAL_ROUTINE_PATTERN =
  /(기상|출근|퇴근|등교|하교|이동|저녁|점심|아침|샤워|정리|취침|수면|준비)/;
const GENERIC_WORK_PATTERN = /^근무(?:$|\s|\()/;
const CLOCK_VALUE_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?$/;

export interface UpcomingScheduleBlock extends ScheduleBlock {
  dayOfWeek: string;
}

function getNowParts(timeZone?: string) {
  const formatter = new Intl.DateTimeFormat("en-US", {
    weekday: "long",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone: normalizeTimeZone(timeZone),
  });

  const parts = formatter.formatToParts(new Date());
  const record = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    weekday: record.weekday ?? "Monday",
    hour: Number(record.hour ?? "0"),
    minute: Number(record.minute ?? "0"),
  };
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

export function minutesFromClock(value: string) {
  const match = CLOCK_VALUE_PATTERN.exec(value);
  if (!match) {
    throw new Error(`Invalid clock value: ${value}`);
  }

  return Number(match[1]) * 60 + Number(match[2]);
}

export function durationInMinutes(startTime: string, endTime: string) {
  const start = minutesFromClock(startTime);
  const end = minutesFromClock(endTime);
  const diff = end - start;
  if (diff > 0) {
    return diff;
  }
  if (diff === 0) {
    return 0;
  }
  return diff + 24 * 60;
}

export function getDailyBlocks(week: WeekScheduleResponse | null, dayOfWeek: string) {
  return week?.week.find((day) => day.dayOfWeek === dayOfWeek)?.blocks ?? [];
}

export function isPlanningSignalBlock(block: ScheduleBlock) {
  if (block.category === "SLEEP") {
    return false;
  }

  if (
    (block.category === "LIFE" || block.category === "TRANSIT") &&
    LOW_SIGNAL_ROUTINE_PATTERN.test(block.activity)
  ) {
    return false;
  }

  if (block.category === "WORK" && GENERIC_WORK_PATTERN.test(block.activity)) {
    return false;
  }

  return true;
}

function getPlanningSignalBlocks(week: WeekScheduleResponse | null, dayOfWeek: string) {
  const blocks = getDailyBlocks(week, dayOfWeek).filter(isPlanningSignalBlock);
  return blocks.length
    ? blocks
    : getDailyBlocks(week, dayOfWeek).filter((block) => block.category !== "SLEEP");
}

function sortBlocksByStart(blocks: ScheduleBlock[]) {
  return [...blocks].sort(
    (left, right) => minutesFromClock(left.startTime) - minutesFromClock(right.startTime),
  );
}

export function getCurrentDayName(timeZone?: string) {
  return getNowParts(timeZone).weekday;
}

export function isBlockActive(block: ScheduleBlock, currentMinutes: number) {
  const start = minutesFromClock(block.startTime);
  const end = start + durationInMinutes(block.startTime, block.endTime);
  return currentMinutes >= start && currentMinutes < end;
}

export function getCurrentMinutes(timeZone?: string) {
  const now = getNowParts(timeZone);
  return now.hour * 60 + now.minute;
}

export function getDashboardFlow(week: WeekScheduleResponse | null, timeZone?: string) {
  const today = getCurrentDayName(timeZone);
  const blocks = getPlanningSignalBlocks(week, today);
  if (!blocks.length) {
    return [];
  }

  const nowMinutes = getCurrentMinutes(timeZone);
  const activeIndex = blocks.findIndex((block) => isBlockActive(block, nowMinutes));
  const nextIndex = blocks.findIndex((block) => minutesFromClock(block.startTime) > nowMinutes);
  const anchor = activeIndex >= 0 ? activeIndex : nextIndex >= 0 ? nextIndex : 0;

  return blocks.slice(anchor, anchor + 4);
}

export function getNextScheduleBlocks(
  week: WeekScheduleResponse | null,
  limit = 2,
  timeZone?: string,
): UpcomingScheduleBlock[] {
  if (!week?.week.length || limit <= 0) {
    return [];
  }

  const today = getCurrentDayName(timeZone);
  const startIndex = DAY_ORDER.indexOf(today as (typeof DAY_ORDER)[number]);

  if (startIndex === -1) {
    return [];
  }

  const nowMinutes = getCurrentMinutes(timeZone);
  const upcomingBlocks: UpcomingScheduleBlock[] = [];

  for (let offset = 0; offset < DAY_ORDER.length && upcomingBlocks.length < limit; offset += 1) {
    const dayOfWeek = DAY_ORDER[(startIndex + offset) % DAY_ORDER.length];
    const dayBlocks = sortBlocksByStart(getPlanningSignalBlocks(week, dayOfWeek));

    for (const block of dayBlocks) {
      if (offset === 0 && minutesFromClock(block.startTime) <= nowMinutes) {
        continue;
      }

      upcomingBlocks.push({
        ...block,
        dayOfWeek,
      });

      if (upcomingBlocks.length >= limit) {
        break;
      }
    }
  }

  return upcomingBlocks;
}

export function getFallbackFocusBlock(week: WeekScheduleResponse | null, timeZone?: string) {
  const today = getCurrentDayName(timeZone);
  const blocks = getPlanningSignalBlocks(week, today);
  const nowMinutes = getCurrentMinutes(timeZone);

  return (
    blocks.find((block) => isBlockActive(block, nowMinutes)) ??
    blocks.find((block) => minutesFromClock(block.startTime) > nowMinutes) ??
    blocks[0] ??
    null
  );
}

export function getWeeklyCompletionScore(goalDays: DailyRoutine[]) {
  const total = goalDays.reduce((sum, day) => sum + day.blocks.length, 0);
  if (total === 0) {
    return 0;
  }

  const growthBlocks = goalDays.reduce(
    (sum, day) => sum + day.blocks.filter((block) => block.category === "GROWTH").length,
    0,
  );
  return Math.round((growthBlocks / total) * 100);
}
