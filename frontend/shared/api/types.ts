export type Nullable<T> = T | null;

export type ISODateTimeString = string;
export type ISODateString = string;

export type PriorityLevel = 1 | 2 | 3 | 4 | 5;

export interface ApiError {
  code: string;
  message: string;
  details?: unknown;
}

export interface ApiMeta {
  page?: number;
  pageSize?: number;
  totalCount?: number;
  [key: string]: unknown;
}

export interface ApiEnvelope<TData, TMeta extends ApiMeta = ApiMeta> {
  success: boolean;
  data: Nullable<TData>;
  meta: TMeta;
  error: Nullable<ApiError>;
}

export type ApiResult<TData, TMeta extends ApiMeta = ApiMeta> = ApiEnvelope<TData, TMeta> | TData;

export type ExternalProvider = "google_calendar" | "google_tasks";
export type PlannerSourceType = "local" | "imported" | "forked" | "tombstone";

export type EventStatus =
  | "planned"
  | "in_progress"
  | "completed"
  | "postponed"
  | "cancelled"
  | "conflict";

export type EventCategory =
  | "study"
  | "work"
  | "health"
  | "life"
  | "meeting"
  | "focus"
  | "admin"
  | "transit"
  | "rest"
  | "other";

export interface Event {
  id: string;
  title: string;
  description?: Nullable<string>;
  startAt: ISODateTimeString;
  endAt: ISODateTimeString;
  actualStartAt?: Nullable<ISODateTimeString>;
  actualEndAt?: Nullable<ISODateTimeString>;
  priority: PriorityLevel;
  status: EventStatus;
  category?: Nullable<EventCategory>;
  goalId?: Nullable<string>;
  taskId?: Nullable<string>;
  sourceType: PlannerSourceType;
  externalProvider?: Nullable<ExternalProvider>;
  externalSourceId?: Nullable<string>;
  createdAt?: Nullable<ISODateTimeString>;
  updatedAt?: Nullable<ISODateTimeString>;
}

export interface EventQuery {
  from: ISODateTimeString;
  to: ISODateTimeString;
}

export interface CreateEventRequest {
  title: string;
  description?: Nullable<string>;
  startAt: ISODateTimeString;
  endAt: ISODateTimeString;
  priority?: PriorityLevel;
  category?: Nullable<EventCategory>;
  goalId?: Nullable<string>;
}

export type UpdateEventRequest = Partial<CreateEventRequest>;

export type EventTriggerSource = "manual" | "system" | "ai" | "sync";

export interface StartEventRequest {
  startedAt: ISODateTimeString;
  triggerSource: EventTriggerSource;
}

export type EventCompletionType = "early" | "on_time" | "late" | "cancelled";

export interface CompleteEventRequest {
  completedAt: ISODateTimeString;
  completionType: EventCompletionType;
  memo?: Nullable<string>;
}

export interface PostponeEventRequest {
  reason: string;
  mode?: "proposal_only" | "ai_reschedule";
  preferredWindowStart?: Nullable<ISODateTimeString>;
  preferredWindowEnd?: Nullable<ISODateTimeString>;
}

export interface ExtendEventRequest {
  extendMinutes: number;
  reason?: Nullable<string>;
}

export type TaskStatus =
  | "pending"
  | "in_progress"
  | "completed"
  | "cancelled"
  | "blocked"
  | "tombstone";

export interface Task {
  id: string;
  title: string;
  description?: Nullable<string>;
  dueDate?: Nullable<ISODateTimeString>;
  estimatedMinutes?: Nullable<number>;
  actualMinutes?: Nullable<number>;
  priority: PriorityLevel;
  goalId?: Nullable<string>;
  eventId?: Nullable<string>;
  status: TaskStatus;
  sourceType: PlannerSourceType;
  externalProvider?: Nullable<ExternalProvider>;
  externalSourceId?: Nullable<string>;
  createdAt?: Nullable<ISODateTimeString>;
  updatedAt?: Nullable<ISODateTimeString>;
}

export interface TaskQuery {
  status?: TaskStatus;
  dueBefore?: ISODateTimeString;
  goalId?: string;
  unassigned?: boolean;
}

export interface CreateTaskRequest {
  title: string;
  description?: Nullable<string>;
  dueDate?: Nullable<ISODateTimeString>;
  estimatedMinutes?: Nullable<number>;
  priority?: PriorityLevel;
  goalId?: Nullable<string>;
  eventId?: Nullable<string>;
}

export type UpdateTaskRequest = Partial<
  CreateTaskRequest & {
    status: TaskStatus;
    actualMinutes: Nullable<number>;
  }
>;

export interface CompleteTaskRequest {
  completedAt: ISODateTimeString;
  actualMinutes?: Nullable<number>;
}

export interface ScheduleTaskRequest {
  startAt: ISODateTimeString;
  endAt: ISODateTimeString;
}

export type GoalCategory =
  | "HEALTH"
  | "CAREER"
  | "FINANCE"
  | "GROWTH"
  | "HOBBY"
  | "OTHER";

export type GoalType = "period" | "quantitative";

export type GoalProgressMethod =
  | "time_accumulation"
  | "count_accumulation"
  | "value_target"
  | "manual"
  | "hybrid";

export type GoalState = "pending" | "in_progress" | "completed" | "at_risk" | "cancelled";

export interface Goal {
  id: string;
  title: string;
  description?: Nullable<string>;
  goalType: GoalType;
  progressMethod: GoalProgressMethod;
  startDate?: Nullable<ISODateString>;
  endDate?: Nullable<ISODateString>;
  priority: PriorityLevel;
  state: GoalState;
  category?: Nullable<GoalCategory>;
  metricUnit?: Nullable<string>;
  targetValue?: Nullable<number>;
  currentValue?: Nullable<number>;
  progressPercent: number;
  relatedTaskCount?: Nullable<number>;
  relatedEventCount?: Nullable<number>;
}

export interface CreateGoalRequest {
  title: string;
  description?: Nullable<string>;
  goalType?: GoalType;
  progressMethod?: GoalProgressMethod;
  startDate?: Nullable<ISODateString>;
  endDate?: Nullable<ISODateString>;
  priority?: PriorityLevel;
  category?: Nullable<GoalCategory>;
  metricUnit?: Nullable<string>;
  targetValue?: Nullable<number>;
  currentValue?: Nullable<number>;
  progressPercent?: Nullable<number>;
  state?: GoalState;
}

export type UpdateGoalRequest = Partial<CreateGoalRequest>;

export interface GoalProgressUpdateRequest {
  deltaValue: number;
  reason?: Nullable<string>;
}

export type FocusItemType = "event" | "task";
export type FocusState =
  | "NO_ACTIVE_ITEM"
  | "UPCOMING_EVENT_READY"
  | "ACTIVE_EVENT"
  | "ACTIVE_RECOMMENDED_TASK"
  | "AWAITING_END_CONFIRMATION"
  | "POSTPONE_REQUESTED"
  | "RESCHEDULE_PENDING"
  | "AUTO_RESCHEDULED"
  | "COMPLETED"
  | "INTERRUPTED"
  | "IDLE"
  | "ACTIVE_TASK"
  | "CONFLICT"
  | "OVERRUN";

export interface FocusGoalSummary {
  id: string;
  title: string;
}

export interface FocusItem {
  type: FocusItemType;
  id: string;
  title: string;
  startAt?: Nullable<ISODateTimeString>;
  endAt?: Nullable<ISODateTimeString>;
  remainingMinutes?: Nullable<number>;
  priority?: Nullable<PriorityLevel>;
  goal?: Nullable<FocusGoalSummary>;
}

export interface FocusCurrentResponse {
  focusState: FocusState;
  currentItem: Nullable<FocusItem>;
  nextItem: Nullable<FocusItem>;
  recommendedTasks: Task[];
  activeSuggestion: Nullable<RescheduleSuggestion>;
  remainingMinutes: number;
}

export interface FocusRecommendationsResponse {
  recommendedTasks: Task[];
  suggestions: RescheduleSuggestion[];
}

export interface StartRecommendedTaskRequest {
  taskId: string;
}

export interface CompleteFocusItemRequest {
  itemType: FocusItemType;
  itemId: string;
  completedAt: ISODateTimeString;
  completionType: EventCompletionType | "completed";
}

export interface PostponeFocusItemRequest {
  itemType: FocusItemType;
  itemId: string;
  reason: string;
  requestAiReschedule?: boolean;
}

export interface ConfirmOverrunRequest {
  itemType: FocusItemType;
  itemId: string;
  action: "continue" | "complete" | "postpone";
  expectedExtraMinutes?: Nullable<number>;
}

export type ChatActionType =
  | "create_event"
  | "update_event"
  | "move_event"
  | "delete_event"
  | "create_task"
  | "update_task"
  | "complete_task"
  | "create_goal"
  | "update_goal"
  | "update_goal_progress"
  | "run_sync"
  | "apply_suggestion"
  | "reject_suggestion"
  | "revert_suggestion"
  | "change_settings"
  | "propose_priority"
  | "explain_only";

export type ChatTargetType =
  | "event"
  | "task"
  | "goal"
  | "suggestion"
  | "settings"
  | "sync"
  | "priority"
  | "focus"
  | "workspace";

export type ChatActionResult = "success" | "proposal_required" | "queued" | "rejected" | "failed";

export interface ChatCommandAction {
  actionType: ChatActionType;
  targetType?: Nullable<ChatTargetType>;
  targetId?: Nullable<string>;
  payload: Record<string, unknown>;
  reason?: Nullable<string>;
  requiresConfirmation: boolean;
  result?: Nullable<ChatActionResult>;
  proposalId?: Nullable<string>;
}

export interface ChatCommandRequest {
  message: string;
}

export interface ChatCommandResponse {
  intent: string;
  actions: ChatCommandAction[];
  message: string;
}

export type RescheduleSuggestionStatus = "pending" | "applied" | "rejected" | "reverted";

export interface RescheduleSuggestion {
  id: string;
  triggerType: string;
  summary: string;
  message?: Nullable<string>;
  reason?: Nullable<string>;
  status: RescheduleSuggestionStatus;
  requiresConfirmation: boolean;
  actions: ChatCommandAction[];
  affectedEventIds?: string[];
  affectedTaskIds?: string[];
  createdAt?: Nullable<ISODateTimeString>;
  appliedAt?: Nullable<ISODateTimeString>;
  canRevert?: boolean;
}

export interface RescheduleRequest {
  triggerType: string;
  targetRangeStart: ISODateTimeString;
  targetRangeEnd: ISODateTimeString;
  reason?: Nullable<string>;
}

export type SyncMode = "inbound" | "outbound" | "bidirectional";
export type SyncRunState = "idle" | "running" | "success" | "partial_failure" | "failure";
export type SyncConflictResolution = "accept_remote" | "fork_local" | "manual_edit";

export interface SyncChannelStatus {
  provider: ExternalProvider;
  lastSyncedAt: Nullable<ISODateTimeString>;
  status: SyncRunState;
  affectedCount: number;
  mode: SyncMode;
  message?: Nullable<string>;
}

export interface SyncStatus {
  googleCalendar: SyncChannelStatus;
  googleTasks: SyncChannelStatus;
}

export interface SyncCalendarRequest {
  mode: SyncMode;
  rangeStart: ISODateTimeString;
  rangeEnd: ISODateTimeString;
  resolvePolicy?: SyncConflictResolution | "proposal_first";
}

export interface SyncTasksRequest {
  mode?: SyncMode;
  rangeStart?: ISODateTimeString;
  rangeEnd?: ISODateTimeString;
  resolvePolicy?: SyncConflictResolution | "proposal_first";
}

export interface ResolveSyncConflictRequest {
  resolution: SyncConflictResolution;
}

export type PriorityProposalStatus = "pending" | "accepted" | "rejected";
export type PrioritySubjectType = "event" | "task" | "goal";

export interface PriorityProposal {
  id: string;
  itemType: PrioritySubjectType;
  itemId: string;
  currentPriority: PriorityLevel;
  proposedPriority: PriorityLevel;
  reason: string;
  status: PriorityProposalStatus;
  requiresConfirmation: boolean;
  createdAt?: Nullable<ISODateTimeString>;
}

export type GoogleConnectionStatus = "CONNECTED" | "DEGRADED" | "NOT_CONNECTED";

export interface AuthSessionResponse {
  authenticated: boolean;
  userId: string;
  email: Nullable<string>;
  displayName: Nullable<string>;
  timezone?: Nullable<string>;
  autoRescheduleEnabled?: boolean;
  focusAutoEnterEnabled?: boolean;
  googleConnectionStatus: GoogleConnectionStatus;
  lastSyncAt: Nullable<string>;
  callbackUrl: string;
}

export interface GoogleStartResponse {
  enabled: boolean;
  url: Nullable<string>;
  message: Nullable<string>;
}

export interface SettingsResponse {
  id: string;
  quietHoursStart: string;
  quietHoursEnd: string;
  bufferMinutes: number;
  overtimeTriggerMinutes: number;
  openGapTriggerMinutes: number;
  interventionFrequency: string;
  timezone?: string;
  autoRescheduleEnabled?: boolean;
  focusAutoEnterEnabled?: boolean;
  focusMode?: "manual" | "auto";
}

export interface SettingsUpdateRequest {
  quietHoursStart?: string;
  quietHoursEnd?: string;
  bufferMinutes?: number;
  overtimeTriggerMinutes?: number;
  openGapTriggerMinutes?: number;
  interventionFrequency?: string;
  timezone?: string;
  autoRescheduleEnabled?: boolean;
  focusAutoEnterEnabled?: boolean;
  focusMode?: "manual" | "auto";
}

export interface OnboardingStatusResponse {
  googleConnected: boolean;
  routinesReady: boolean;
  goalsReady: boolean;
  preferencesReady: boolean;
  scheduleReady: boolean;
  nextStep: string;
}

export type DayKey =
  | "Monday"
  | "Tuesday"
  | "Wednesday"
  | "Thursday"
  | "Friday"
  | "Saturday"
  | "Sunday";

export type ScheduleCategory =
  | "WORK"
  | "LIFE"
  | "TRANSIT"
  | "GROWTH"
  | "HOBBY"
  | "SLEEP"
  | "ADMIN";

export type ScheduleSourceType = "DEFAULT_ROUTINE" | "GEMMA_IMPORT" | "MANUAL";

export interface ScheduleBlockResponse {
  id: string;
  startTime: string;
  endTime: string;
  activity: string;
  category: ScheduleCategory;
  note?: Nullable<string>;
  sourceType: ScheduleSourceType;
  sourceRef?: Nullable<string>;
}

export interface DailyScheduleResponse {
  dayOfWeek: DayKey;
  blocks: ScheduleBlockResponse[];
}

export interface WeekScheduleResponse {
  week: DailyScheduleResponse[];
}

export interface ScheduleImportRequest {
  rawText: string;
  replaceExisting: boolean;
}

export interface ScheduleBlockWriteRequest {
  dayOfWeek: DayKey;
  startTime: string;
  endTime: string;
  activity: string;
  category: ScheduleCategory;
  note?: Nullable<string>;
}

export type GoalStatus = "PENDING" | "IN_PROGRESS" | "COMPLETED" | "FAILED";

export interface GoalResponse {
  id: string;
  parentId?: Nullable<string>;
  title: string;
  description?: Nullable<string>;
  category: GoalCategory;
  status: GoalStatus;
  progress: number;
}

export interface GoalCreateRequest {
  parentId?: Nullable<string>;
  title: string;
  description?: Nullable<string>;
  category?: GoalCategory;
  status?: GoalStatus;
  progress?: number;
}
