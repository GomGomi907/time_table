"use client";

import {
  findDaySchedule,
  formatTimeRange,
  getNextBlock,
  getTodayDayKey,
  sortBlocks,
  toMinutes,
} from "@/features/schedule/utils";
import type {
  GoalResponse,
  ScheduleBlockResponse,
  WeekScheduleResponse,
} from "@/shared/api/types";
import type { TaskResourceItem } from "@/components/widgets/useTasksResource";

export type FocusState = "ACTIVE_EVENT" | "GAP" | "CONFLICT" | "EMPTY";

export interface FocusGoalReference {
  id: string;
  title: string;
}

export interface FocusTaskReference {
  id: string;
  title: string;
  estimatedMinutes?: number | null;
  dueAt?: string | null;
}

export interface FocusItem {
  type: "event" | "task";
  id: string;
  title: string;
  startAt?: string | null;
  endAt?: string | null;
  startTimeLabel?: string | null;
  endTimeLabel?: string | null;
  remainingMinutes?: number;
  priority?: number | null;
  note?: string | null;
  goal?: FocusGoalReference | null;
  relatedTasks?: FocusTaskReference[];
  actualStartAt?: string | null;
  actualEndAt?: string | null;
  isDelayed?: boolean;
}

export interface FocusSuggestion {
  id: string;
  title: string;
  detail: string;
  tone?: "default" | "accent" | "success" | "danger";
}

export interface FocusWorkspaceData {
  focusState: FocusState;
  currentItem: FocusItem | null;
  nextItem: FocusItem | null;
  recommendedTasks: FocusTaskReference[];
  activeSuggestion: FocusSuggestion | null;
  remainingMinutes: number;
  overlappingItems: FocusItem[];
}

export const formatFocusStateLabel = (focusState: FocusState) => {
  switch (focusState) {
    case "ACTIVE_EVENT":
      return "실행 중";
    case "GAP":
      return "빈 시간";
    case "CONFLICT":
      return "충돌 감지";
    default:
      return "예정 없음";
  }
};

export const formatMinutesLabel = (minutes: number) => {
  if (minutes <= 0) {
    return "지금";
  }

  const rounded = Math.round(minutes);
  const hours = Math.floor(rounded / 60);
  const remainder = rounded % 60;

  if (hours > 0) {
    return `${hours}시간 ${remainder}분`;
  }

  return `${remainder}분`;
};

export const formatFocusRange = (item: FocusItem | null) => {
  if (!item) {
    return "일정 정보 없음";
  }

  if (item.startTimeLabel && item.endTimeLabel) {
    return formatTimeRange(item.startTimeLabel, item.endTimeLabel);
  }

  if (item.startAt && item.endAt) {
    const start = new Date(item.startAt);
    const end = new Date(item.endAt);

    if (!Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime())) {
      return `${new Intl.DateTimeFormat("ko-KR", {
        hour: "2-digit",
        minute: "2-digit",
      }).format(start)} - ${new Intl.DateTimeFormat("ko-KR", {
        hour: "2-digit",
        minute: "2-digit",
      }).format(end)}`;
    }
  }

  return "시간 정보 없음";
};

const isBlockActive = (block: ScheduleBlockResponse, now: Date) => {
  const currentMinutes = now.getHours() * 60 + now.getMinutes();
  const start = toMinutes(block.startTime);
  let end = toMinutes(block.endTime);
  let comparableCurrent = currentMinutes;

  if (end <= start) {
    end += 1440;
    if (comparableCurrent < start) {
      comparableCurrent += 1440;
    }
  }

  return comparableCurrent >= start && comparableCurrent < end;
};

const findActiveBlocks = (blocks: ScheduleBlockResponse[], now: Date) =>
  sortBlocks(blocks).filter((block) => isBlockActive(block, now));

const toFocusTaskReference = (task: TaskResourceItem): FocusTaskReference => ({
  id: task.id,
  title: task.title,
  estimatedMinutes: task.estimatedMinutes ?? null,
  dueAt: task.dueDate ?? task.due ?? null,
});

const inferGoalReference = (
  goals: GoalResponse[],
  block: ScheduleBlockResponse | null
): FocusGoalReference | null => {
  const activeGoals = goals.filter((goal) => goal.status === "IN_PROGRESS");

  if (activeGoals.length === 0) {
    return null;
  }

  if (!block) {
    return {
      id: activeGoals[0].id,
      title: activeGoals[0].title,
    };
  }

  if (block.category === "GROWTH") {
    const growthGoal = activeGoals.find((goal) => goal.category === "GROWTH");

    if (growthGoal) {
      return {
        id: growthGoal.id,
        title: growthGoal.title,
      };
    }
  }

  return {
    id: activeGoals[0].id,
    title: activeGoals[0].title,
  };
};

const toFocusItemFromBlock = (
  block: ScheduleBlockResponse,
  goals: GoalResponse[],
  relatedTasks: TaskResourceItem[],
  remainingMinutes: number
): FocusItem => ({
  type: "event",
  id: block.id,
  title: block.activity,
  startTimeLabel: block.startTime.slice(0, 5),
  endTimeLabel: block.endTime.slice(0, 5),
  remainingMinutes,
  note: block.note ?? null,
  goal: inferGoalReference(goals, block),
  relatedTasks: relatedTasks.slice(0, 2).map(toFocusTaskReference),
});

export const buildDerivedFocusData = ({
  schedule,
  goals,
  tasks,
  now,
}: {
  schedule: WeekScheduleResponse | null;
  goals: GoalResponse[];
  tasks: TaskResourceItem[];
  now: Date;
}): FocusWorkspaceData => {
  const todayBlocks = sortBlocks(findDaySchedule(schedule, getTodayDayKey(now)).blocks);
  const activeBlocks = findActiveBlocks(todayBlocks, now);
  const nextBlock = getNextBlock(todayBlocks, now);
  const pendingTasks = tasks.filter((task) => task.status?.toLowerCase() !== "completed");
  const unassignedTasks = pendingTasks.filter(
    (task) => !task.eventId && !task.scheduledStartAt
  );

  if (activeBlocks.length > 1) {
    const overlappingItems = activeBlocks.map((block) =>
      toFocusItemFromBlock(block, goals, pendingTasks, 0)
    );

    return {
      focusState: "CONFLICT",
      currentItem: overlappingItems[0] ?? null,
      nextItem: nextBlock
        ? toFocusItemFromBlock(nextBlock, goals, pendingTasks, 0)
        : null,
      recommendedTasks: unassignedTasks.slice(0, 3).map(toFocusTaskReference),
      activeSuggestion: {
        id: "derived-conflict",
        title: "현재 시간대에 겹치는 일정이 있습니다",
        detail:
          "한 시점에는 하나의 주요 일정만 수행한다는 MVP 정책에 맞춰 충돌 해소가 먼저 필요합니다.",
        tone: "danger",
      },
      remainingMinutes: 0,
      overlappingItems,
    };
  }

  if (activeBlocks.length === 1) {
    const currentBlock = activeBlocks[0];
    const start = toMinutes(currentBlock.startTime);
    let end = toMinutes(currentBlock.endTime);
    let comparableNow = now.getHours() * 60 + now.getMinutes();

    if (end <= start) {
      end += 1440;
      if (comparableNow < start) {
        comparableNow += 1440;
      }
    }

    const remainingMinutes = Math.max(0, end - comparableNow);

    return {
      focusState: "ACTIVE_EVENT",
      currentItem: toFocusItemFromBlock(
        currentBlock,
        goals,
        pendingTasks,
        remainingMinutes
      ),
      nextItem: nextBlock
        ? toFocusItemFromBlock(nextBlock, goals, pendingTasks, 0)
        : null,
      recommendedTasks: unassignedTasks.slice(0, 3).map(toFocusTaskReference),
      activeSuggestion:
        nextBlock && toMinutes(nextBlock.startTime) - toMinutes(currentBlock.endTime) <= 10
          ? {
              id: "derived-tight-buffer",
              title: "다음 일정과 버퍼가 좁습니다",
              detail:
                "현재 블록 종료 체크와 다음 일정 준비를 미리 시작하면 충돌 없이 전환하기 쉽습니다.",
              tone: "accent",
            }
          : null,
      remainingMinutes,
      overlappingItems: [],
    };
  }

  if (nextBlock) {
    const nowMinutes = now.getHours() * 60 + now.getMinutes();
    const remainingMinutes = Math.max(0, toMinutes(nextBlock.startTime) - nowMinutes);
    const recommended = unassignedTasks.length > 0 ? unassignedTasks : pendingTasks;

    return {
      focusState: "GAP",
      currentItem: null,
      nextItem: toFocusItemFromBlock(nextBlock, goals, pendingTasks, 0),
      recommendedTasks: recommended.slice(0, 3).map(toFocusTaskReference),
      activeSuggestion:
        recommended.length > 0
          ? {
              id: "derived-gap",
              title: `${formatMinutesLabel(remainingMinutes)} 빈 시간이 생겼습니다`,
              detail: `"${recommended[0].title}" 같은 태스크를 짧게 시작하기 좋은 구간입니다.`,
              tone: "success",
            }
          : null,
      remainingMinutes,
      overlappingItems: [],
    };
  }

  return {
    focusState: "EMPTY",
    currentItem: null,
    nextItem: null,
    recommendedTasks: pendingTasks.slice(0, 3).map(toFocusTaskReference),
    activeSuggestion:
      pendingTasks.length > 0
        ? {
            id: "derived-empty",
            title: "오늘 시간표는 비어 있지만 할 일은 남아 있습니다",
            detail: "미배치 태스크를 하나 골라 짧은 집중 세션으로 전환해 보세요.",
            tone: "accent",
          }
        : null,
    remainingMinutes: 0,
    overlappingItems: [],
  };
};
