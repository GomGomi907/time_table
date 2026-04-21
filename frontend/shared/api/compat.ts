import type {
  CreateEventRequest,
  CreateGoalRequest,
  DayKey,
  Event,
  EventCategory,
  EventQuery,
  Goal,
  GoalCreateRequest,
  GoalResponse,
  GoalState,
  GoalStatus,
  PriorityLevel,
  ScheduleBlockResponse,
  ScheduleBlockWriteRequest,
  ScheduleCategory,
  ScheduleSourceType,
  Task,
  TaskStatus,
  WeekScheduleResponse,
} from "./types";

const DAY_ORDER: DayKey[] = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
];

const DAY_INDEX: Record<DayKey, number> = {
  Monday: 0,
  Tuesday: 1,
  Wednesday: 2,
  Thursday: 3,
  Friday: 4,
  Saturday: 5,
  Sunday: 6,
};

const EVENT_CATEGORY_TO_SCHEDULE: Record<EventCategory, ScheduleCategory> = {
  study: "GROWTH",
  work: "WORK",
  health: "LIFE",
  life: "LIFE",
  meeting: "WORK",
  focus: "GROWTH",
  admin: "ADMIN",
  transit: "TRANSIT",
  rest: "SLEEP",
  other: "HOBBY",
};

const SCHEDULE_CATEGORY_TO_EVENT: Record<ScheduleCategory, EventCategory> = {
  WORK: "work",
  LIFE: "life",
  TRANSIT: "transit",
  GROWTH: "study",
  HOBBY: "other",
  SLEEP: "rest",
  ADMIN: "admin",
};

const EVENT_SOURCE_TO_SCHEDULE: Record<string, ScheduleSourceType> = {
  local: "MANUAL",
  forked: "MANUAL",
  imported: "GEMMA_IMPORT",
  tombstone: "GEMMA_IMPORT",
};

const GOAL_STATE_TO_STATUS: Record<GoalState, GoalStatus> = {
  pending: "PENDING",
  in_progress: "IN_PROGRESS",
  completed: "COMPLETED",
  at_risk: "FAILED",
  cancelled: "FAILED",
};

const GOAL_STATUS_TO_STATE: Record<GoalStatus, GoalState> = {
  PENDING: "pending",
  IN_PROGRESS: "in_progress",
  COMPLETED: "completed",
  FAILED: "at_risk",
};

const pad2 = (value: number) => String(value).padStart(2, "0");

const toPriority = (value: number | null | undefined): PriorityLevel => {
  if (value === 1 || value === 2 || value === 3 || value === 4 || value === 5) {
    return value;
  }

  return 3;
};

const formatDateAsIsoDate = (date: Date) => {
  const year = date.getFullYear();
  const month = pad2(date.getMonth() + 1);
  const day = pad2(date.getDate());
  return `${year}-${month}-${day}`;
};

const formatClock = (date: Date) => `${pad2(date.getHours())}:${pad2(date.getMinutes())}`;

export const getStartOfCurrentWeek = (now: Date = new Date()) => {
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  const day = start.getDay();
  const mondayOffset = (day + 6) % 7;
  start.setDate(start.getDate() - mondayOffset);
  return start;
};

export const getCurrentWeekEventQuery = (now: Date = new Date()): EventQuery => {
  const start = getStartOfCurrentWeek(now);
  const end = new Date(start);
  end.setDate(end.getDate() + 7);

  return {
    from: start.toISOString(),
    to: end.toISOString(),
  };
};

export const getDateForDayKey = (dayKey: DayKey, now: Date = new Date()) => {
  const startOfWeek = getStartOfCurrentWeek(now);
  const date = new Date(startOfWeek);
  date.setDate(startOfWeek.getDate() + DAY_INDEX[dayKey]);
  return date;
};

const toDateRange = (date: Date, startTime: string, endTime: string) => {
  const [startHour, startMinute] = startTime.split(":").map(Number);
  const [endHour, endMinute] = endTime.split(":").map(Number);

  const start = new Date(date);
  start.setHours(startHour, startMinute, 0, 0);

  const end = new Date(date);
  end.setHours(endHour, endMinute, 0, 0);

  if (end <= start) {
    end.setDate(end.getDate() + 1);
  }

  return { start, end };
};

export const scheduleCategoryToEventCategory = (category: ScheduleCategory): EventCategory =>
  SCHEDULE_CATEGORY_TO_EVENT[category];

export const eventCategoryToScheduleCategory = (category?: string | null): ScheduleCategory => {
  if (!category) {
    return "ADMIN";
  }

  return EVENT_CATEGORY_TO_SCHEDULE[category as EventCategory] ?? "ADMIN";
};

export const scheduleBlockToCreateEventRequest = (
  payload: ScheduleBlockWriteRequest,
  now: Date = new Date()
): CreateEventRequest => {
  const date = getDateForDayKey(payload.dayOfWeek, now);
  const { start, end } = toDateRange(date, payload.startTime, payload.endTime);

  return {
    title: payload.activity.trim(),
    description: payload.note?.trim() ? payload.note.trim() : null,
    startAt: start.toISOString(),
    endAt: end.toISOString(),
    category: scheduleCategoryToEventCategory(payload.category),
    priority: 3,
  };
};

const getDayKeyFromDate = (date: Date): DayKey => {
  const day = date.getDay();

  switch (day) {
    case 0:
      return "Sunday";
    case 1:
      return "Monday";
    case 2:
      return "Tuesday";
    case 3:
      return "Wednesday";
    case 4:
      return "Thursday";
    case 5:
      return "Friday";
    default:
      return "Saturday";
  }
};

export const eventToScheduleBlock = (event: Event): ScheduleBlockResponse => {
  const start = new Date(event.startAt);
  const end = new Date(event.endAt);

  return {
    id: event.id,
    startTime: formatClock(start),
    endTime: formatClock(end),
    activity: event.title,
    category: eventCategoryToScheduleCategory(event.category),
    note: event.description ?? null,
    sourceType: EVENT_SOURCE_TO_SCHEDULE[event.sourceType] ?? "MANUAL",
    sourceRef: event.externalSourceId ?? null,
  };
};

const toEventList = (value: unknown): Event[] => {
  if (Array.isArray(value)) {
    return value as Event[];
  }

  if (value && typeof value === "object") {
    const record = value as Record<string, unknown>;

    if (Array.isArray(record.data)) {
      return record.data as Event[];
    }

    if (Array.isArray(record.events)) {
      return record.events as Event[];
    }

    if (Array.isArray(record.items)) {
      return record.items as Event[];
    }
  }

  return [];
};

export const toWeekScheduleResponse = (events: Event[] | unknown): WeekScheduleResponse => {
  const grouped = new Map<DayKey, ScheduleBlockResponse[]>();
  const normalizedEvents = toEventList(events);

  DAY_ORDER.forEach((day) => {
    grouped.set(day, []);
  });

  normalizedEvents.forEach((event) => {
    const dayKey = getDayKeyFromDate(new Date(event.startAt));
    grouped.get(dayKey)?.push(eventToScheduleBlock(event));
  });

  return {
    week: DAY_ORDER.map((dayOfWeek) => ({
      dayOfWeek,
      blocks: grouped.get(dayOfWeek) ?? [],
    })),
  };
};

export const goalStateToLegacyStatus = (state: GoalState): GoalStatus => GOAL_STATE_TO_STATUS[state];

export const goalStatusToState = (status: GoalStatus | GoalState | undefined): GoalState => {
  if (!status) {
    return "pending";
  }

  if (status in GOAL_STATUS_TO_STATE) {
    return GOAL_STATUS_TO_STATE[status as GoalStatus];
  }

  return status as GoalState;
};

export const toGoalResponse = (goal: Goal): GoalResponse => ({
  id: goal.id,
  parentId: null,
  title: goal.title,
  description: goal.description ?? null,
  category: goal.category ?? "OTHER",
  status: goalStateToLegacyStatus(goal.state),
  progress: goal.progressPercent,
});

export const legacyGoalRequestToCreateGoalRequest = (
  payload: GoalCreateRequest,
  now: Date = new Date()
): CreateGoalRequest => {
  const startDate = formatDateAsIsoDate(now);
  const endDate = formatDateAsIsoDate(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 7));

  return {
    title: payload.title.trim(),
    description: payload.description?.trim() ? payload.description.trim() : null,
    category: payload.category ?? "GROWTH",
    state: goalStatusToState(payload.status),
    progressPercent: payload.progress ?? 0,
    goalType: "period",
    progressMethod: "manual",
    priority: 3,
    startDate,
    endDate,
  };
};

export const toGoal = (goal: Goal | GoalResponse): Goal => {
  if ("state" in goal) {
    return {
      ...goal,
      priority: toPriority(goal.priority),
      progressPercent: goal.progressPercent ?? 0,
      goalType: goal.goalType ?? "period",
      progressMethod: goal.progressMethod ?? "manual",
      state: goal.state ?? "pending",
    };
  }

  return {
    id: goal.id,
    title: goal.title,
    description: goal.description ?? null,
    goalType: "period",
    progressMethod: "manual",
    startDate: null,
    endDate: null,
    priority: 3,
    state: goalStatusToState(goal.status),
    category: goal.category,
    metricUnit: null,
    targetValue: null,
    currentValue: null,
    progressPercent: goal.progress,
    relatedTaskCount: null,
    relatedEventCount: null,
  };
};

type LegacyTask = {
  id: string;
  title: string;
  notes?: string;
  status?: string;
  due?: string;
};

const normalizeTaskStatus = (status: string | undefined): TaskStatus => {
  switch (status?.toLowerCase()) {
    case "completed":
      return "completed";
    case "in_progress":
    case "needsaction":
      return "in_progress";
    case "cancelled":
      return "cancelled";
    case "blocked":
      return "blocked";
    default:
      return "pending";
  }
};

export const toTask = (task: Task | LegacyTask): Task => {
  if ("sourceType" in task) {
    return {
      ...task,
      priority: toPriority(task.priority),
    };
  }

  return {
    id: task.id,
    title: task.title,
    description: task.notes ?? null,
    dueDate: task.due ?? null,
    estimatedMinutes: null,
    actualMinutes: null,
    priority: 3,
    goalId: null,
    eventId: null,
    status: normalizeTaskStatus(task.status),
    sourceType: "imported",
    externalProvider: "google_tasks",
    externalSourceId: task.id,
    createdAt: null,
    updatedAt: null,
  };
};
