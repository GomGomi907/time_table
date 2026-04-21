export interface AuthSessionResponse {
  authenticated: boolean;
  userId: string;
  email: string | null;
  displayName: string | null;
  googleConnectionStatus: GoogleConnectionStatus;
  lastSyncAt: string | null;
  callbackUrl: string;
}

export type GoogleConnectionStatus = "CONNECTED" | "DEGRADED" | "NOT_CONNECTED";

export interface GoogleStartResponse {
  enabled: boolean;
  url: string | null;
  message: string | null;
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
  note?: string | null;
  sourceType: ScheduleSourceType;
  sourceRef?: string | null;
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
  note?: string | null;
}

export type GoalCategory =
  | "HEALTH"
  | "CAREER"
  | "FINANCE"
  | "GROWTH"
  | "HOBBY"
  | "OTHER";

export type GoalStatus =
  | "PENDING"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "FAILED";

export interface GoalResponse {
  id: string;
  parentId?: string | null;
  title: string;
  description?: string | null;
  category: GoalCategory;
  status: GoalStatus;
  progress: number;
}

export interface GoalCreateRequest {
  parentId?: string | null;
  title: string;
  description?: string | null;
  category?: GoalCategory;
  status?: GoalStatus;
  progress?: number;
}

export interface SettingsResponse {
  id: string;
  quietHoursStart: string;
  quietHoursEnd: string;
  bufferMinutes: number;
  overtimeTriggerMinutes: number;
  openGapTriggerMinutes: number;
  interventionFrequency: string;
}

export interface SettingsUpdateRequest {
  quietHoursStart?: string;
  quietHoursEnd?: string;
  bufferMinutes?: number;
  overtimeTriggerMinutes?: number;
  openGapTriggerMinutes?: number;
  interventionFrequency?: string;
}

export interface OnboardingStatusResponse {
  googleConnected: boolean;
  routinesReady: boolean;
  goalsReady: boolean;
  preferencesReady: boolean;
  scheduleReady: boolean;
  nextStep: string;
}
