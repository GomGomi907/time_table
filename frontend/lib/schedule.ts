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
  WORK: "Deep Work",
  GROWTH: "Growth",
  LIFE: "Life",
  TRANSIT: "Transit",
  HOBBY: "Hobby",
  SLEEP: "Sleep",
  ADMIN: "Admin",
  HEALTH: "Health",
};

export const PIXELS_PER_MINUTE = 1.15;
export const DAY_TRACK_HEIGHT = 24 * 60 * PIXELS_PER_MINUTE;
const MIN_RENDERED_BLOCK_MINUTES = 15;
const BLOCK_HORIZONTAL_INSET = 6;
const BLOCK_HORIZONTAL_GAP = 6;

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

export function getLaidOutBlocks(blocks: ScheduleBlock[]): PositionedScheduleBlock[] {
  if (!blocks.length) {
    return [];
  }

  const prepared = [...blocks]
    .map((block) => {
      const start = minutesFromClock(block.startTime);
      const actualDuration = durationInMinutes(block.startTime, block.endTime);

      return {
        block,
        start,
        end: start + actualDuration,
        displayDuration: Math.max(actualDuration, MIN_RENDERED_BLOCK_MINUTES),
      };
    })
    .sort((left, right) => {
      if (left.start !== right.start) {
        return left.start - right.start;
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
      const column = columnEnds.findIndex((end) => end <= item.start);
      const nextColumn = column === -1 ? columnEnds.length : column;
      columnEnds[nextColumn] = item.end;

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
        top: `${item.start * PIXELS_PER_MINUTE}px`,
        height: `${blockHeight}px`,
        left: horizontal.left,
        width: horizontal.width,
        isCompact: item.displayDuration <= 30 || columns > 1,
        isTight: item.displayDuration <= 20,
      });
    });

    cluster = [];
    clusterEnd = -1;
  }

  prepared.forEach((item) => {
    if (!cluster.length) {
      cluster = [item];
      clusterEnd = item.end;
      return;
    }

    if (item.start < clusterEnd) {
      cluster.push(item);
      clusterEnd = Math.max(clusterEnd, item.end);
      return;
    }

    flushCluster();
    cluster = [item];
    clusterEnd = item.end;
  });

  flushCluster();

  return positioned;
}

export function getDailyBlocks(week: WeekScheduleResponse | null, dayOfWeek: string) {
  return week?.week.find((day) => day.dayOfWeek === dayOfWeek)?.blocks ?? [];
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
  const blocks = getDailyBlocks(week, today);
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
    const dayBlocks = sortBlocksByStart(getDailyBlocks(week, dayOfWeek));

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
  const blocks = getDailyBlocks(week, today);
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
