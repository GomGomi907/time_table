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
};

export const DAY_FULL_LABELS: Record<string, string> = {
  Monday: "월요일",
  Tuesday: "화요일",
  Wednesday: "수요일",
  Thursday: "목요일",
  Friday: "금요일",
  Saturday: "토요일",
  Sunday: "일요일",
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

export const PIXELS_PER_MINUTE = 1.14;
export const DEFAULT_WEEK_VIEW_START_MINUTES = 6 * 60;
export const DEFAULT_WEEK_VIEW_END_MINUTES = 24 * 60;
export const DAY_TRACK_HEIGHT =
  (DEFAULT_WEEK_VIEW_END_MINUTES - DEFAULT_WEEK_VIEW_START_MINUTES) * PIXELS_PER_MINUTE;
const MIN_RENDERED_BLOCK_MINUTES = 30;
const BLOCK_HORIZONTAL_INSET = 6;
const BLOCK_HORIZONTAL_GAP = 6;
const LOW_SIGNAL_ROUTINE_PATTERN =
  /(기상|출근|퇴근|등교|하교|이동|저녁|점심|아침|샤워|정리|취침|수면|준비)/;
const GENERIC_WORK_PATTERN = /^근무(?:$|\s|\()/;

export interface PositionedScheduleBlock extends ScheduleBlock {
  top: string;
  height: string;
  left: string;
  width: string;
  isCompact: boolean;
  isTight: boolean;
}

export interface UpcomingScheduleBlock extends ScheduleBlock {
  dayOfWeek: string;
}

function getNowParts(timeZone?: string) {
  const formatter = new Intl.DateTimeFormat("en-US", {
    weekday: "long",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone,
  });

  const parts = formatter.formatToParts(new Date());
  const record = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    weekday: record.weekday ?? "Monday",
    hour: Number(record.hour ?? "0"),
    minute: Number(record.minute ?? "0"),
  };
}

export function minutesFromClock(value: string) {
  const [hour, minute] = value.split(":").map(Number);
  return hour * 60 + minute;
}

export function durationInMinutes(startTime: string, endTime: string) {
  const start = minutesFromClock(startTime);
  const end = minutesFromClock(endTime);
  const diff = end - start;
  return diff > 0 ? diff : diff + 24 * 60;
}

export function getBlockPosition(block: ScheduleBlock) {
  const start = minutesFromClock(block.startTime);
  const duration = Math.max(durationInMinutes(block.startTime, block.endTime), MIN_RENDERED_BLOCK_MINUTES);

  return {
    top: `${start * PIXELS_PER_MINUTE}px`,
    height: `${duration * PIXELS_PER_MINUTE}px`,
  };
}

function getHorizontalBlockStyle(column: number, columns: number) {
  const sharedWidth = `((100% - ${BLOCK_HORIZONTAL_INSET * 2}px - ${(columns - 1) * BLOCK_HORIZONTAL_GAP}px) / ${columns})`;

  return {
    width: `calc(${sharedWidth})`,
    left: `calc(${BLOCK_HORIZONTAL_INSET}px + (${column} * (${sharedWidth} + ${BLOCK_HORIZONTAL_GAP}px)))`,
  };
}

export function getLaidOutBlocks(
  blocks: ScheduleBlock[],
  viewportStart = DEFAULT_WEEK_VIEW_START_MINUTES,
  viewportEnd = DEFAULT_WEEK_VIEW_END_MINUTES,
): PositionedScheduleBlock[] {
  if (!blocks.length) {
    return [];
  }

  const prepared = [...blocks]
    .map((block) => {
      const start = minutesFromClock(block.startTime);
      const actualDuration = durationInMinutes(block.startTime, block.endTime);
      const shouldShiftAfterMidnight =
        start < viewportStart && start + 24 * 60 < viewportEnd;
      const layoutStart = shouldShiftAfterMidnight ? start + 24 * 60 : start;
      const actualEnd = layoutStart + actualDuration;
      const visibleStart = Math.max(layoutStart, viewportStart);
      const visibleEnd = Math.min(actualEnd, viewportEnd);
      const visibleDuration = visibleEnd - visibleStart;

      if (visibleDuration <= 0) {
        return null;
      }

      return {
        block,
        start,
        layoutStart: visibleStart,
        end: visibleEnd,
        actualDuration,
        displayDuration: Math.max(visibleDuration, MIN_RENDERED_BLOCK_MINUTES),
      };
    })
    .filter((item): item is NonNullable<typeof item> => item !== null)
    .sort((left, right) => {
      if (left.layoutStart !== right.layoutStart) {
        return left.layoutStart - right.layoutStart;
      }

      return right.end - left.end;
    });

  const positioned: PositionedScheduleBlock[] = [];
  let cluster: typeof prepared = [];
  let clusterEnd = -1;

  function flushCluster() {
    if (!cluster.length) {
      return;
    }

    const columnEnds: number[] = [];
    const clusterItems = cluster.map((item) => {
      const visualEnd = item.layoutStart + item.displayDuration;
      const column = columnEnds.findIndex((end) => end <= item.layoutStart);
      const nextColumn = column === -1 ? columnEnds.length : column;
      columnEnds[nextColumn] = visualEnd;

      return {
        item,
        column: nextColumn,
      };
    });

    const columns = Math.max(columnEnds.length, 1);

    clusterItems.forEach(({ item, column }) => {
      const blockHeight = item.displayDuration * PIXELS_PER_MINUTE;
      const horizontal = getHorizontalBlockStyle(column, columns);

      positioned.push({
        ...item.block,
        top: `${(item.layoutStart - viewportStart) * PIXELS_PER_MINUTE}px`,
        height: `${blockHeight}px`,
        left: horizontal.left,
        width: horizontal.width,
        isCompact: item.actualDuration <= 40 || item.displayDuration <= 40 || columns > 1,
        isTight: item.actualDuration <= 25,
      });
    });

    cluster = [];
    clusterEnd = -1;
  }

  prepared.forEach((item) => {
    if (!cluster.length) {
      cluster = [item];
      clusterEnd = item.layoutStart + item.displayDuration;
      return;
    }

    if (item.layoutStart < clusterEnd) {
      cluster.push(item);
      clusterEnd = Math.max(clusterEnd, item.layoutStart + item.displayDuration);
      return;
    }

    flushCluster();
    cluster = [item];
    clusterEnd = item.layoutStart + item.displayDuration;
  });

  flushCluster();

  return positioned;
}

export function getDailyBlocks(week: WeekScheduleResponse | null, dayOfWeek: string) {
  return week?.week.find((day) => day.dayOfWeek === dayOfWeek)?.blocks ?? [];
}

function dedupeScheduleBlocks(blocks: ScheduleBlock[]) {
  const seen = new Set<string>();
  return blocks.filter((block) => {
    const key = [
      block.startTime,
      block.endTime,
      block.activity.trim(),
      block.category,
      block.note?.trim() ?? "",
    ].join("|");
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
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
  const blocks = dedupeScheduleBlocks(getDailyBlocks(week, dayOfWeek).filter(isPlanningSignalBlock));
  return blocks.length
    ? blocks
    : dedupeScheduleBlocks(getDailyBlocks(week, dayOfWeek).filter((block) => block.category !== "SLEEP"));
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
