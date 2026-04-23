package com.timetable.operator.auth.application;

import com.timetable.operator.agent.application.RescheduleSuggestionService;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.RescheduleSuggestionTriggerType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.domain.OnboardingProfile;
import com.timetable.operator.auth.infrastructure.OnboardingProfileRepository;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.goals.infrastructure.GoalRepository;
import com.timetable.operator.schedule.application.ScheduleBlockRules;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.settings.application.SettingsService;
import com.timetable.operator.sync.application.SyncOrchestrationService;
import com.timetable.operator.sync.domain.SyncTargetSystem;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final Duration BOOTSTRAP_REFRESH_WINDOW = Duration.ofMinutes(15);
    private static final List<DayOfWeek> WEEKDAYS = List.of(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
    );
    private static final List<OnboardingQuestionResponse> QUESTIONS = List.of(
            new OnboardingQuestionResponse(
                    "wakeTime",
                    "보통 몇 시에 일어나세요?",
                    "캘린더에 잘 안 남는 기상 패턴을 먼저 잡습니다.",
                    List.of(
                            new OnboardingQuestionOptionResponse("06:00", "06:00", "이른 아침부터 시작"),
                            new OnboardingQuestionOptionResponse("06:30", "06:30", "조금 일찍 여유 있게"),
                            new OnboardingQuestionOptionResponse("07:00", "07:00", "가장 일반적인 패턴"),
                            new OnboardingQuestionOptionResponse("07:30", "07:30", "출근 직전까지 아끼는 편"),
                            new OnboardingQuestionOptionResponse("08:00", "08:00", "늦게 시작하는 편")
                    )
            ),
            new OnboardingQuestionResponse(
                    "workStartTime",
                    "보통 몇 시쯤 출근 또는 업무를 시작하세요?",
                    "AI가 고정 이동/준비 시간을 먼저 보호합니다.",
                    List.of(
                            new OnboardingQuestionOptionResponse("08:00", "08:00", "아침형 근무"),
                            new OnboardingQuestionOptionResponse("08:30", "08:30", "조금 이른 편"),
                            new OnboardingQuestionOptionResponse("09:00", "09:00", "표준 출근"),
                            new OnboardingQuestionOptionResponse("09:30", "09:30", "유연 근무"),
                            new OnboardingQuestionOptionResponse("10:00", "10:00", "늦게 시작")
                    )
            ),
            new OnboardingQuestionResponse(
                    "dinnerTime",
                    "저녁은 보통 몇 시쯤 드세요?",
                    "퇴근 후 회복 시간대를 먼저 비워 두겠습니다.",
                    List.of(
                            new OnboardingQuestionOptionResponse("18:00", "18:00", "이른 저녁"),
                            new OnboardingQuestionOptionResponse("18:30", "18:30", "평균보다 조금 빠름"),
                            new OnboardingQuestionOptionResponse("19:00", "19:00", "가장 일반적인 패턴"),
                            new OnboardingQuestionOptionResponse("19:30", "19:30", "약간 늦은 편"),
                            new OnboardingQuestionOptionResponse("20:00", "20:00", "늦은 저녁")
                    )
            ),
            new OnboardingQuestionResponse(
                    "sleepTime",
                    "보통 몇 시에 잠드세요?",
                    "수면 보호 구간을 기본 루틴으로 고정합니다.",
                    List.of(
                            new OnboardingQuestionOptionResponse("22:30", "22:30", "일찍 쉬는 편"),
                            new OnboardingQuestionOptionResponse("23:00", "23:00", "안정적인 루틴"),
                            new OnboardingQuestionOptionResponse("23:30", "23:30", "조금 늦는 편"),
                            new OnboardingQuestionOptionResponse("00:00", "00:00", "자정 전후"),
                            new OnboardingQuestionOptionResponse("00:30", "00:30", "야행성에 가까움")
                    )
            ),
            new OnboardingQuestionResponse(
                    "weekendStyle",
                    "주말 한 칸은 어떻게 쓰는 편인가요?",
                    "AI가 주말 블록 한 칸도 함께 제안합니다.",
                    List.of(
                            new OnboardingQuestionOptionResponse("recovery", "회복 중심", "충전과 정리에 더 가깝게"),
                            new OnboardingQuestionOptionResponse("balanced", "균형 있게", "조금 쉬고 조금 정리"),
                            new OnboardingQuestionOptionResponse("productive", "몰입형", "프로젝트나 공부에 확실히 사용")
                    )
            )
    );

    private final CurrentUserProvider currentUserProvider;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final OnboardingProfileRepository onboardingProfileRepository;
    private final SettingsService settingsService;
    private final SyncOrchestrationService syncOrchestrationService;
    private final RescheduleSuggestionService rescheduleSuggestionService;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final ScheduleBlockRepository scheduleBlockRepository;
    private final GoalRepository goalRepository;
    private final ScheduleBlockRules scheduleBlockRules;

    @Transactional
    public OnboardingStatusResponse getStatus() {
        AppUser user = currentUserProvider.getCurrentUser();
        settingsService.getOrCreatePreferences();

        OnboardingProfile profile = getOrCreateProfile(user.getId());
        boolean googleConnected = isGoogleConnected(user.getId());
        ImportedWorkspaceSummaryResponse importSummary = buildImportSummary(user.getId(), googleConnected);
        RescheduleSuggestionService.RescheduleSuggestionResponse suggestion = findLatestOnboardingSuggestion();

        return buildStatus(user, profile, googleConnected, importSummary, suggestion);
    }

    @Transactional
    public OnboardingBootstrapResponse bootstrap() {
        AppUser user = currentUserProvider.getCurrentUser();
        settingsService.getOrCreatePreferences();

        OnboardingProfile profile = getOrCreateProfile(user.getId());
        boolean googleConnected = isGoogleConnected(user.getId());
        boolean syncTriggered = false;
        String message;

        if (!googleConnected) {
            message = "Google 연결은 조용히 다시 확인하되, 지금은 현재 워크스페이스 데이터와 답변을 기준으로 먼저 맞춥니다.";
        } else if (shouldRefreshBootstrap(profile.getBootstrapCompletedAt())) {
            syncTriggered = true;
            try {
                triggerSilentSync();
                message = "Google Calendar와 Tasks 연결을 확인하고 최근 데이터를 조용히 불러왔습니다.";
            } catch (RuntimeException exception) {
                message = "Google 연결은 확인됐지만 가져오기 중 일부가 지연되어 현재 저장된 데이터를 기준으로 먼저 진행합니다.";
            }
        } else {
            message = "방금 가져온 데이터를 그대로 이어서 사용합니다.";
        }

        profile.setBootstrapCompletedAt(Instant.now());
        profile = onboardingProfileRepository.save(profile);

        ImportedWorkspaceSummaryResponse importSummary = buildImportSummary(user.getId(), googleConnected);
        RescheduleSuggestionService.RescheduleSuggestionResponse suggestion = findLatestOnboardingSuggestion();
        OnboardingStatusResponse status = buildStatus(user, profile, googleConnected, importSummary, suggestion);

        return new OnboardingBootstrapResponse(status, syncTriggered, message);
    }

    @Transactional
    public OnboardingAnswersResponse saveAnswers(OnboardingAnswersRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        OnboardingProfile profile = getOrCreateProfile(user.getId());
        Map<String, String> answers = normalizeAnswers(request.answers());

        profile.setWakeTime(parseTimeAnswer(answers, "wakeTime"));
        profile.setWorkStartTime(parseTimeAnswer(answers, "workStartTime"));
        profile.setDinnerTime(parseTimeAnswer(answers, "dinnerTime"));
        profile.setSleepTime(parseTimeAnswer(answers, "sleepTime"));
        profile.setWeekendStyle(parseChoiceAnswer(answers, "weekendStyle"));
        profile = onboardingProfileRepository.save(profile);

        settingsService.update(new SettingsService.SettingsUpdateRequest(
                profile.getSleepTime(),
                profile.getWakeTime(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        RescheduleSuggestionService.RescheduleSuggestionResponse suggestion = createOnboardingSuggestion(user.getId(), profile);
        ImportedWorkspaceSummaryResponse importSummary = buildImportSummary(user.getId(), isGoogleConnected(user.getId()));
        OnboardingStatusResponse status = buildStatus(user, profile, isGoogleConnected(user.getId()), importSummary, suggestion);

        return new OnboardingAnswersResponse(status, "답변을 저장했고, 바로 첫 AI 일정 조율 제안을 준비했습니다.");
    }

    @Transactional
    public OnboardingCompletionResponse complete(OnboardingCompletionRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        OnboardingProfile profile = getOrCreateProfile(user.getId());
        RescheduleSuggestionService.RescheduleSuggestionResponse appliedSuggestion = null;

        if (request != null && request.applySuggestion()) {
            if (request.suggestionId() == null || request.suggestionId().isBlank()) {
                throw new IllegalArgumentException("적용할 suggestionId가 필요합니다.");
            }
            appliedSuggestion = rescheduleSuggestionService.applySuggestion(
                    UUID.fromString(request.suggestionId()),
                    new RescheduleSuggestionService.SuggestionDecisionRequest("온보딩에서 자동 적용")
            );
        }

        profile.setOnboardingCompletedAt(Instant.now());
        profile = onboardingProfileRepository.save(profile);

        boolean googleConnected = isGoogleConnected(user.getId());
        ImportedWorkspaceSummaryResponse importSummary = buildImportSummary(user.getId(), googleConnected);
        RescheduleSuggestionService.RescheduleSuggestionResponse suggestion =
                appliedSuggestion != null ? appliedSuggestion : findLatestOnboardingSuggestion();

        return new OnboardingCompletionResponse(
                buildStatus(user, profile, googleConnected, importSummary, suggestion),
                appliedSuggestion,
                appliedSuggestion == null
                        ? "온보딩을 마쳤습니다. 이후에도 대시보드에서 제안을 다시 받을 수 있습니다."
                        : "첫 AI 제안을 반영하고 온보딩을 마쳤습니다."
        );
    }

    private OnboardingProfile getOrCreateProfile(UUID userId) {
        return onboardingProfileRepository.findByUserId(userId)
                .orElseGet(() -> onboardingProfileRepository.save(newProfile(userId)));
    }

    private OnboardingProfile newProfile(UUID userId) {
        OnboardingProfile profile = new OnboardingProfile();
        profile.setUserId(userId);
        return profile;
    }

    private boolean isGoogleConnected(UUID userId) {
        return calendarConnectionRepository.findByUserIdAndProvider(userId, "google")
                .map(connection -> connection.getStatus() == CalendarConnectionStatus.CONNECTED)
                .orElse(false);
    }

    private boolean shouldRefreshBootstrap(Instant bootstrapCompletedAt) {
        return bootstrapCompletedAt == null
                || bootstrapCompletedAt.isBefore(Instant.now().minus(BOOTSTRAP_REFRESH_WINDOW));
    }

    private void triggerSilentSync() {
        SyncOrchestrationService.ManualSyncRequest request =
                new SyncOrchestrationService.ManualSyncRequest(null, null, null, null);
        syncOrchestrationService.requestManualSync(SyncTargetSystem.GOOGLE_CALENDAR, request);
        syncOrchestrationService.requestManualSync(SyncTargetSystem.GOOGLE_TASKS, request);
    }

    private ImportedWorkspaceSummaryResponse buildImportSummary(UUID userId, boolean googleConnected) {
        SyncOrchestrationService.SyncStatusSnapshot syncStatus = syncOrchestrationService.getCurrentUserStatus();
        long eventCount = eventRepository.countByUserId(userId);
        long taskCount = taskRepository.countByUserId(userId);
        long scheduleBlockCount = scheduleBlockRepository.countByUserId(userId);
        long goalCount = goalRepository.existsByUserId(userId) ? goalRepository.findByUserId(userId).size() : 0;

        String workspaceSummary = "일정 %d건, 할 일 %d건, 루틴 블록 %d건을 기준으로 첫 제안을 맞춥니다."
                .formatted(eventCount, taskCount, scheduleBlockCount);
        String sourceLabel = googleConnected
                ? "Google 연결 데이터와 현재 워크스페이스를 함께 봅니다."
                : "Google 연결이 확인되지 않아 현재 워크스페이스 데이터만 먼저 반영합니다.";

        return new ImportedWorkspaceSummaryResponse(
                eventCount,
                taskCount,
                scheduleBlockCount,
                goalCount,
                syncStatus.data().googleCalendar().lastSyncedAt(),
                syncStatus.data().googleTasks().lastSyncedAt(),
                workspaceSummary,
                sourceLabel
        );
    }

    private OnboardingStatusResponse buildStatus(
            AppUser user,
            OnboardingProfile profile,
            boolean googleConnected,
            ImportedWorkspaceSummaryResponse importSummary,
            RescheduleSuggestionService.RescheduleSuggestionResponse suggestion
    ) {
        OnboardingProfileResponse profileResponse = toProfileResponse(profile);
        OnboardingExperienceResponse experience = suggestion == null
                ? null
                : new OnboardingExperienceResponse(
                        suggestion,
                        buildPreviewItems(suggestion.commandBatch()),
                        buildExperienceSummary(suggestion)
                );
        boolean profileReady = hasRequiredAnswers(profile);
        boolean completed = profile.getOnboardingCompletedAt() != null;

        return new OnboardingStatusResponse(
                googleConnected,
                profileReady,
                experience != null,
                completed,
                determineNextStep(profile, experience, completed),
                user.getDisplayName(),
                user.getTimezone(),
                profile.getBootstrapCompletedAt(),
                importSummary,
                QUESTIONS,
                profileResponse,
                experience
        );
    }

    private String determineNextStep(
            OnboardingProfile profile,
            OnboardingExperienceResponse experience,
            boolean completed
    ) {
        if (completed) {
            return "dashboard";
        }
        if (profile.getBootstrapCompletedAt() == null) {
            return "bootstrap";
        }
        if (!hasRequiredAnswers(profile)) {
            return "questions";
        }
        if (experience == null) {
            return "preview";
        }
        return "review";
    }

    private OnboardingProfileResponse toProfileResponse(OnboardingProfile profile) {
        if (!hasAnyAnswer(profile)) {
            return null;
        }
        return new OnboardingProfileResponse(
                formatTime(profile.getWakeTime()),
                formatTime(profile.getWorkStartTime()),
                formatTime(profile.getDinnerTime()),
                formatTime(profile.getSleepTime()),
                profile.getWeekendStyle(),
                formatTime(profile.getSleepTime()),
                formatTime(profile.getWakeTime())
        );
    }

    private boolean hasAnyAnswer(OnboardingProfile profile) {
        return profile.getWakeTime() != null
                || profile.getWorkStartTime() != null
                || profile.getDinnerTime() != null
                || profile.getSleepTime() != null
                || profile.getWeekendStyle() != null;
    }

    private boolean hasRequiredAnswers(OnboardingProfile profile) {
        return profile.getWakeTime() != null
                && profile.getWorkStartTime() != null
                && profile.getDinnerTime() != null
                && profile.getSleepTime() != null
                && profile.getWeekendStyle() != null;
    }

    private Map<String, String> normalizeAnswers(Map<String, String> answers) {
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("온보딩 질문 답변이 비어 있습니다.");
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (OnboardingQuestionResponse question : QUESTIONS) {
            String value = answers.get(question.id());
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("필수 답변이 누락되었습니다: " + question.id());
            }
            boolean validOption = question.options().stream()
                    .anyMatch(option -> option.value().equals(value.trim()));
            if (!validOption) {
                throw new IllegalArgumentException("허용되지 않는 답변 값입니다: " + question.id());
            }
            normalized.put(question.id(), value.trim());
        }
        return Map.copyOf(normalized);
    }

    private LocalTime parseTimeAnswer(Map<String, String> answers, String questionId) {
        return LocalTime.parse(parseChoiceAnswer(answers, questionId));
    }

    private String parseChoiceAnswer(Map<String, String> answers, String questionId) {
        String value = answers.get(questionId);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("답변 값이 비어 있습니다: " + questionId);
        }
        return value.trim();
    }

    private RescheduleSuggestionService.RescheduleSuggestionResponse createOnboardingSuggestion(
            UUID userId,
            OnboardingProfile profile
    ) {
        List<PlannedRoutineBlock> applicableBlocks = selectApplicableBlocks(userId, buildPlannedBlocks(profile));
        StructuredAiCommandBatch batch;

        if (applicableBlocks.isEmpty()) {
            batch = new StructuredAiCommandBatch(
                    "온보딩 루틴 후보를 검토했습니다.",
                    "이미 저장된 루틴과 겹치지 않는 새 블록이 거의 없어, 기존 캘린더 흐름을 우선 유지하도록 두었습니다.",
                    List.of(new StructuredAiCommand(
                            AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                            AgentCommandTargetType.NONE.wireValue(),
                            null,
                            Map.of(
                                    "summary", "현재 저장된 일정과 답변을 학습했고, 이후 대시보드에서 재조율 제안을 이어서 제공합니다."
                            ),
                            "onboarding profile captured",
                            false
                    ))
            );
        } else {
            List<StructuredAiCommand> commands = applicableBlocks.stream()
                    .map(this::toCreateEventCommand)
                    .toList();
            batch = new StructuredAiCommandBatch(
                    "답변과 현재 일정 패턴을 바탕으로 첫 루틴 블록을 제안합니다.",
                    "캘린더에 잘 안 남는 수면, 출근 준비, 저녁, 주말 패턴을 먼저 고정해 이후 AI 재조율의 기준점으로 삼습니다.",
                    commands
            );
        }

        return rescheduleSuggestionService.createSuggestionFromBatch(
                RescheduleSuggestionTriggerType.ONBOARDING_BOOTSTRAP,
                "온보딩용 첫 AI 일정 조율 제안을 만들었습니다.",
                "사용자가 답한 고정 패턴을 기준으로 첫 주간 운영 루틴을 제안합니다.",
                batch.explanation(),
                batch
        );
    }

    private List<PlannedRoutineBlock> buildPlannedBlocks(OnboardingProfile profile) {
        if (!hasRequiredAnswers(profile)) {
            throw new IllegalStateException("온보딩 답변이 아직 완성되지 않았습니다.");
        }

        List<PlannedRoutineBlock> blocks = new ArrayList<>();
        LocalTime wakeTime = profile.getWakeTime();
        LocalTime workStartTime = profile.getWorkStartTime();
        LocalTime dinnerTime = profile.getDinnerTime();
        LocalTime sleepTime = profile.getSleepTime();

        for (DayOfWeek day : DayOfWeek.values()) {
            blocks.add(new PlannedRoutineBlock(
                    day,
                    sleepTime,
                    wakeTime,
                    "수면 보호",
                    ScheduleCategory.SLEEP,
                    "수면 시간이 흔들리지 않도록 매일 기본 보호 구간으로 잡습니다."
            ));
        }

        LocalTime transitStart = recommendedTransitStart(wakeTime, workStartTime);
        if (transitStart != null) {
            for (DayOfWeek day : WEEKDAYS) {
                blocks.add(new PlannedRoutineBlock(
                        day,
                        transitStart,
                        workStartTime,
                        "출근 준비 / 이동",
                        ScheduleCategory.TRANSIT,
                        "출근 직전 준비와 이동 시간을 캘린더 밖 고정 패턴으로 보호합니다."
                ));
            }
        }

        for (DayOfWeek day : WEEKDAYS) {
            blocks.add(new PlannedRoutineBlock(
                    day,
                    dinnerTime,
                    dinnerTime.plusMinutes(60),
                    "저녁 / 회복",
                    ScheduleCategory.LIFE,
                    "퇴근 후 회복 시간을 기본 블록으로 남겨 AI가 과하게 잠식하지 않게 합니다."
            ));
        }

        blocks.add(weekendAnchor(profile.getWeekendStyle()));
        return blocks;
    }

    private PlannedRoutineBlock weekendAnchor(String weekendStyle) {
        return switch (weekendStyle == null ? "" : weekendStyle.trim().toLowerCase(Locale.ROOT)) {
            case "recovery" -> new PlannedRoutineBlock(
                    DayOfWeek.SATURDAY,
                    LocalTime.of(10, 0),
                    LocalTime.of(12, 0),
                    "회복 / 리셋 시간",
                    ScheduleCategory.LIFE,
                    "주말 한 칸은 비워 두고 회복 흐름을 지키도록 제안합니다."
            );
            case "productive" -> new PlannedRoutineBlock(
                    DayOfWeek.SATURDAY,
                    LocalTime.of(9, 0),
                    LocalTime.of(12, 0),
                    "주말 집중 블록",
                    ScheduleCategory.GROWTH,
                    "주말에도 확실히 몰입할 수 있는 긴 블록을 먼저 확보합니다."
            );
            default -> new PlannedRoutineBlock(
                    DayOfWeek.SATURDAY,
                    LocalTime.of(10, 0),
                    LocalTime.of(12, 0),
                    "개인 프로젝트 / 정리",
                    ScheduleCategory.GROWTH,
                    "쉬는 흐름을 해치지 않으면서도 가볍게 진도를 낼 수 있게 제안합니다."
            );
        };
    }

    private LocalTime recommendedTransitStart(LocalTime wakeTime, LocalTime workStartTime) {
        LocalTime nominalStart = workStartTime.minusMinutes(45);
        LocalTime wakeAwareStart = wakeTime.plusMinutes(15);
        LocalTime start = nominalStart.isBefore(wakeAwareStart) ? wakeAwareStart : nominalStart;

        if (durationMinutes(start, workStartTime) < 20) {
            return null;
        }
        return start;
    }

    private List<PlannedRoutineBlock> selectApplicableBlocks(UUID userId, List<PlannedRoutineBlock> candidates) {
        List<ScheduleBlock> acceptedBlocks = new ArrayList<>();
        List<PlannedRoutineBlock> acceptedPlans = new ArrayList<>();

        for (PlannedRoutineBlock candidate : candidates) {
            ScheduleBlock block = toScheduleBlock(userId, candidate);
            List<ScheduleBlock> batch = new ArrayList<>(acceptedBlocks);
            batch.add(block);
            try {
                scheduleBlockRules.validateBatch(userId, batch, false);
                acceptedBlocks.add(block);
                acceptedPlans.add(candidate);
            } catch (IllegalArgumentException ignored) {
                // Keep only routine blocks that fit around current user data.
            }
        }

        return acceptedPlans;
    }

    private ScheduleBlock toScheduleBlock(UUID userId, PlannedRoutineBlock plan) {
        ScheduleBlock block = new ScheduleBlock();
        block.setUserId(userId);
        block.setDayOfWeek(plan.dayOfWeek());
        block.setStartTime(plan.startTime());
        block.setEndTime(plan.endTime());
        block.setActivity(plan.activity());
        block.setCategory(plan.category());
        return block;
    }

    private StructuredAiCommand toCreateEventCommand(PlannedRoutineBlock plan) {
        return new StructuredAiCommand(
                AgentCommandActionType.CREATE_EVENT.wireValue(),
                AgentCommandTargetType.EVENT.wireValue(),
                null,
                Map.of(
                        "dayOfWeek", plan.dayOfWeek().name(),
                        "startTime", plan.startTime().toString(),
                        "endTime", plan.endTime().toString(),
                        "activity", plan.activity(),
                        "category", plan.category().name(),
                        "note", plan.reason()
                ),
                plan.reason(),
                true
        );
    }

    private RescheduleSuggestionService.RescheduleSuggestionResponse findLatestOnboardingSuggestion() {
        return rescheduleSuggestionService.getCurrentUserSuggestions().stream()
                .filter(suggestion -> RescheduleSuggestionTriggerType.ONBOARDING_BOOTSTRAP.wireValue()
                        .equalsIgnoreCase(suggestion.triggerType()))
                .findFirst()
                .orElse(null);
    }

    private List<OnboardingPreviewItemResponse> buildPreviewItems(StructuredAiCommandBatch batch) {
        Map<String, PreviewAccumulator> grouped = new LinkedHashMap<>();

        for (StructuredAiCommand command : batch.commands()) {
            if (!AgentCommandActionType.CREATE_EVENT.wireValue().equals(command.actionType())) {
                continue;
            }
            String dayOfWeek = readString(command.payload(), "dayOfWeek", "day_of_week");
            String startTime = readString(command.payload(), "startTime", "start_time");
            String endTime = readString(command.payload(), "endTime", "end_time");
            String activity = readString(command.payload(), "activity");
            String category = readString(command.payload(), "category");
            String reason = command.reason();

            if (dayOfWeek == null || startTime == null || endTime == null || activity == null || category == null) {
                continue;
            }

            String key = activity + "|" + startTime + "|" + endTime + "|" + category + "|" + reason;
            PreviewAccumulator accumulator = grouped.computeIfAbsent(
                    key,
                    ignored -> new PreviewAccumulator(activity, startTime, endTime, category, reason)
            );
            accumulator.days().add(DayOfWeek.valueOf(dayOfWeek));
        }

        return grouped.values().stream()
                .map(accumulator -> new OnboardingPreviewItemResponse(
                        accumulator.activity(),
                        daysLabel(accumulator.days()),
                        accumulator.startTime(),
                        accumulator.endTime(),
                        accumulator.category(),
                        accumulator.reason()
                ))
                .toList();
    }

    private String buildExperienceSummary(RescheduleSuggestionService.RescheduleSuggestionResponse suggestion) {
        int executableCount = (int) suggestion.commandBatch().commands().stream()
                .filter(command -> AgentCommandActionType.CREATE_EVENT.wireValue().equals(command.actionType()))
                .count();

        if (executableCount == 0) {
            return "답변은 저장됐고, 현재는 기존 일정 흐름을 우선 유지하도록 했습니다.";
        }
        return "AI가 지금 당장 반영할 수 있는 고정 루틴 %d개를 먼저 골랐습니다.".formatted(executableCount);
    }

    private String daysLabel(List<DayOfWeek> days) {
        EnumSet<DayOfWeek> daySet = EnumSet.copyOf(days);
        if (daySet.size() == 7) {
            return "매일";
        }
        if (daySet.equals(EnumSet.copyOf(WEEKDAYS))) {
            return "월-금";
        }
        return daySet.stream()
                .sorted()
                .map(this::koreanDayLabel)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String koreanDayLabel(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            case SATURDAY -> "토";
            case SUNDAY -> "일";
        };
    }

    private int durationMinutes(LocalTime startTime, LocalTime endTime) {
        int diff = endTime.toSecondOfDay() / 60 - startTime.toSecondOfDay() / 60;
        return diff > 0 ? diff : diff + (24 * 60);
    }

    private String readString(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public record OnboardingStatusResponse(
            boolean googleConnected,
            boolean profileReady,
            boolean aiExperienceReady,
            boolean completed,
            String nextStep,
            String displayName,
            String timezone,
            Instant bootstrappedAt,
            ImportedWorkspaceSummaryResponse importSummary,
            List<OnboardingQuestionResponse> questions,
            OnboardingProfileResponse profile,
            OnboardingExperienceResponse experience
    ) {
    }

    public record OnboardingBootstrapResponse(
            OnboardingStatusResponse status,
            boolean syncTriggered,
            String message
    ) {
    }

    public record OnboardingAnswersRequest(
            Map<String, String> answers
    ) {
    }

    public record OnboardingAnswersResponse(
            OnboardingStatusResponse status,
            String message
    ) {
    }

    public record OnboardingCompletionRequest(
            boolean applySuggestion,
            String suggestionId
    ) {
    }

    public record OnboardingCompletionResponse(
            OnboardingStatusResponse status,
            RescheduleSuggestionService.RescheduleSuggestionResponse appliedSuggestion,
            String message
    ) {
    }

    public record ImportedWorkspaceSummaryResponse(
            long calendarEventCount,
            long taskCount,
            long scheduleBlockCount,
            long goalCount,
            Instant lastCalendarSyncAt,
            Instant lastTaskSyncAt,
            String workspaceSummary,
            String sourceLabel
    ) {
    }

    public record OnboardingQuestionResponse(
            String id,
            String title,
            String description,
            List<OnboardingQuestionOptionResponse> options
    ) {
    }

    public record OnboardingQuestionOptionResponse(
            String value,
            String label,
            String helper
    ) {
    }

    public record OnboardingProfileResponse(
            String wakeTime,
            String workStartTime,
            String dinnerTime,
            String sleepTime,
            String weekendStyle,
            String quietHoursStart,
            String quietHoursEnd
    ) {
    }

    public record OnboardingExperienceResponse(
            RescheduleSuggestionService.RescheduleSuggestionResponse suggestion,
            List<OnboardingPreviewItemResponse> previewItems,
            String summary
    ) {
    }

    public record OnboardingPreviewItemResponse(
            String title,
            String days,
            String startTime,
            String endTime,
            String category,
            String reason
    ) {
    }

    private record PlannedRoutineBlock(
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            String activity,
            ScheduleCategory category,
            String reason
    ) {
    }

    private record PreviewAccumulator(
            String activity,
            String startTime,
            String endTime,
            String category,
            String reason,
            List<DayOfWeek> days
    ) {
        private PreviewAccumulator(String activity, String startTime, String endTime, String category, String reason) {
            this(activity, startTime, endTime, category, reason, new ArrayList<>());
        }
    }
}
