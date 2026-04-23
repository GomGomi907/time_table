export interface AuthSession {
  authenticated: boolean;
  userId: string;
  email: string;
  displayName: string;
  timezone: string;
  autoRescheduleEnabled: boolean;
  focusAutoEnterEnabled: boolean;
  googleConnectionStatus: string;
  lastSyncAt: string | null;
  callbackUrl: string;
}

export interface GoogleStartResponse {
  enabled: boolean;
  url: string;
  message: string | null;
}

export interface OnboardingStatus {
  googleConnected: boolean;
  profileReady: boolean;
  aiExperienceReady: boolean;
  completed: boolean;
  nextStep: string;
  displayName: string;
  timezone: string;
  bootstrappedAt: string | null;
  importSummary: OnboardingImportSummary;
  questions: OnboardingQuestion[];
  profile: OnboardingProfile | null;
  experience: OnboardingExperience | null;
}

export interface OnboardingImportSummary {
  calendarEventCount: number;
  taskCount: number;
  scheduleBlockCount: number;
  goalCount: number;
  lastCalendarSyncAt: string | null;
  lastTaskSyncAt: string | null;
  workspaceSummary: string;
  sourceLabel: string;
}

export interface OnboardingQuestion {
  id: string;
  title: string;
  description: string;
  options: OnboardingQuestionOption[];
}

export interface OnboardingQuestionOption {
  value: string;
  label: string;
  helper: string;
}

export interface OnboardingProfile {
  wakeTime: string;
  workStartTime: string;
  dinnerTime: string;
  sleepTime: string;
  weekendStyle: string;
  quietHoursStart: string;
  quietHoursEnd: string;
}

export interface OnboardingPreviewItem {
  title: string;
  days: string;
  startTime: string;
  endTime: string;
  category: string;
  reason: string;
}

export interface OnboardingExperience {
  suggestion: RescheduleSuggestion;
  previewItems: OnboardingPreviewItem[];
  summary: string;
}

export interface OnboardingBootstrapResponse {
  status: OnboardingStatus;
  syncTriggered: boolean;
  message: string;
}

export interface OnboardingAnswersRequest {
  answers: Record<string, string>;
}

export interface OnboardingAnswersResponse {
  status: OnboardingStatus;
  message: string;
}

export interface OnboardingCompletionRequest {
  applySuggestion: boolean;
  suggestionId?: string;
}

export interface OnboardingCompletionResponse {
  status: OnboardingStatus;
  appliedSuggestion: RescheduleSuggestion | null;
  message: string;
}

export interface SettingsResponse {
  id: string;
  quietHoursStart: string;
  quietHoursEnd: string;
  bufferMinutes: number;
  overtimeTriggerMinutes: number;
  openGapTriggerMinutes: number;
  interventionFrequency: string;
  timezone: string;
  autoRescheduleEnabled: boolean;
  focusAutoEnterEnabled: boolean;
}

export interface SettingsUpdateRequest {
  quietHoursStart?: string;
  quietHoursEnd?: string;
  interventionFrequency?: string;
  timezone?: string;
  autoRescheduleEnabled?: boolean;
  focusAutoEnterEnabled?: boolean;
}

export interface CreateGoalRequest {
  title: string;
  description?: string;
  category?: string;
  priority?: number;
}

export interface WeekScheduleResponse {
  week: DailyRoutine[];
}

export interface DailyRoutine {
  dayOfWeek: string;
  blocks: ScheduleBlock[];
}

export interface ScheduleBlock {
  id: string;
  startTime: string;
  endTime: string;
  activity: string;
  category: string;
  note: string | null;
  sourceType: string;
  sourceRef: string | null;
}

export interface Goal {
  id: string;
  parentId: string | null;
  title: string;
  description: string | null;
  category: string;
  status: string;
  progress: number;
  goalType: string;
  metricUnit: string | null;
  targetValue: number | null;
  currentValue: number | null;
  progressRule: string | null;
  startDate: string | null;
  endDate: string | null;
  priority: number;
}

export interface Task {
  id: string;
  title: string;
  description: string | null;
  dueDate: string | null;
  estimatedMinutes: number;
  actualMinutes: number | null;
  priority: number;
  status: string;
  category: string | null;
  sourceType: string;
  syncState: string;
  goalId: string | null;
  eventId: string | null;
}

export interface GoalSummary {
  id: string;
  title: string;
}

export interface FocusCurrentItem {
  type: string;
  id: string;
  title: string;
  startAt: string;
  endAt: string;
  remainingMinutes: number;
  priority: number;
  goal: GoalSummary | null;
}

export interface FocusNextItem {
  type: string;
  id: string;
  title: string;
  startAt: string;
}

export interface RecommendedTask {
  id: string;
  title: string;
  priority: number;
  estimatedMinutes: number;
  dueDate: string | null;
}

export interface FocusCurrentView {
  focusState: string;
  currentItem: FocusCurrentItem | null;
  nextItem: FocusNextItem | null;
  recommendedTasks: RecommendedTask[];
  activeSuggestion: unknown;
  remainingMinutes: number | null;
}

export interface StructuredAiCommand {
  actionType: string;
  targetType: string;
  targetId: string | null;
  payload: Record<string, unknown>;
  reason: string | null;
  executable: boolean;
}

export interface StructuredAiCommandBatch {
  summary: string;
  explanation: string;
  commands: StructuredAiCommand[];
}

export interface RescheduleSuggestion {
  id: string;
  triggerType: string;
  status: string;
  summary: string;
  reason: string | null;
  explanation: string;
  commandBatch: StructuredAiCommandBatch;
  createdAt: string;
  appliedAt: string | null;
  rejectedAt: string | null;
  revertedAt: string | null;
}

export interface SyncStatusTarget {
  lastSyncedAt: string | null;
  status: string;
  affectedCount: number;
  mode: string;
  triggerSource: string | null;
  detail: string;
}

export interface SyncStatusResponse {
  googleCalendar: SyncStatusTarget;
  googleTasks: SyncStatusTarget;
}

export interface SyncStatusMeta {
  webhookTarget?: string;
  pollingTarget?: string;
  pollingEnabled?: boolean;
  pendingConflictCount?: number;
}

export interface SyncStatusEnvelope {
  data: SyncStatusResponse;
  meta: SyncStatusMeta;
}

export interface ManualSyncResponse {
  syncRunId: string;
  targetSystem: string;
  status: string;
  mode: string;
  triggerSource: string;
  rangeStart: string | null;
  rangeEnd: string | null;
  affectedCount: number;
  detail: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface CreateScheduleBlockRequest {
  dayOfWeek: string;
  startTime: string;
  endTime: string;
  activity: string;
  category: string;
  note?: string;
}
