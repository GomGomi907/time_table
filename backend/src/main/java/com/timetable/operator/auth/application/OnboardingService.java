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
                    "",
                    List.of(
                            new OnboardingQuestionOptionResponse("05:30", "05:30", ""),
                            new OnboardingQuestionOptionResponse("06:00", "06:00", ""),
                            new OnboardingQuestionOptionResponse("06:30", "06:30", ""),
                            new OnboardingQuestionOptionResponse("07:00", "07:00", ""),
                            new OnboardingQuestionOptionResponse("07:30", "07:30", ""),
                            new OnboardingQuestionOptionResponse("08:00", "08:00", ""),
                            new OnboardingQuestionOptionResponse("08:30", "08:30", ""),
                            new OnboardingQuestionOptionResponse("09:00", "09:00", ""),
                            new OnboardingQuestionOptionResponse("09:30", "09:30", "")
                    )
            ),
            new OnboardingQuestionResponse(
                    "workStartTime",
                    "출근이나 업무는 보통 몇 시에 시작하세요?",
                    "",
                    List.of(
                            new OnboardingQuestionOptionResponse("07:30", "07:30", ""),
                            new OnboardingQuestionOptionResponse("08:00", "08:00", ""),
                            new OnboardingQuestionOptionResponse("08:30", "08:30", ""),
                            new OnboardingQuestionOptionResponse("09:00", "09:00", ""),
                            new OnboardingQuestionOptionResponse("09:30", "09:30", ""),
                            new OnboardingQuestionOptionResponse("10:00", "10:00", ""),
                            new OnboardingQuestionOptionResponse("10:30", "10:30", ""),
                            new OnboardingQuestionOptionResponse("11:00", "11:00", "")
                    )
            ),
            new OnboardingQuestionResponse(
                    "dinnerTime",
                    "저녁은 보통 몇 시에 드세요?",
                    "",
                    List.of(
                            new OnboardingQuestionOptionResponse("17:30", "17:30", ""),
                            new OnboardingQuestionOptionResponse("18:00", "18:00", ""),
                            new OnboardingQuestionOptionResponse("18:30", "18:30", ""),
                            new OnboardingQuestionOptionResponse("19:00", "19:00", ""),
                            new OnboardingQuestionOptionResponse("19:30", "19:30", ""),
                            new OnboardingQuestionOptionResponse("20:00", "20:00", ""),
                            new OnboardingQuestionOptionResponse("20:30", "20:30", ""),
                            new OnboardingQuestionOptionResponse("21:00", "21:00", "")
                    )
            ),
            new OnboardingQuestionResponse(
                    "sleepTime",
                    "보통 몇 시에 잠드세요?",
                    "",
                    List.of(
                            new OnboardingQuestionOptionResponse("22:00", "22:00", ""),
                            new OnboardingQuestionOptionResponse("22:30", "22:30", ""),
                            new OnboardingQuestionOptionResponse("23:00", "23:00", ""),
                            new OnboardingQuestionOptionResponse("23:30", "23:30", ""),
                            new OnboardingQuestionOptionResponse("00:00", "00:00", ""),
                            new OnboardingQuestionOptionResponse("00:30", "00:30", ""),
                            new OnboardingQuestionOptionResponse("01:00", "01:00", ""),
                            new OnboardingQuestionOptionResponse("01:30", "01:30", "")
                    )
            ),
            new OnboardingQuestionResponse(
                    "weekendStyle",
                    "주말은 보통 어떻게 보내세요?",
                    "",
                    List.of(
                            new OnboardingQuestionOptionResponse("recovery", "쉬는 쪽", ""),
                            new OnboardingQuestionOptionResponse("balanced", "반반", ""),
                            new OnboardingQuestionOptionResponse("productive", "작업 쪽", "")
                    )
            ),
            new OnboardingQuestionResponse(
                    "focusSessionMinutes",
                    "한 번에 몇 분 정도 일하기 좋으세요?",
                    "",
                    List.of(
                            new OnboardingQuestionOptionResponse("25", "25분", ""),
                            new OnboardingQuestionOptionResponse("45", "45분", ""),
                            new OnboardingQuestionOptionResponse("60", "60분", ""),
                            new OnboardingQuestionOptionResponse("90", "90분", "")
                    )
            ),
            new OnboardingQuestionResponse(
                    "focusBreakMinutes",
                    "일한 뒤 몇 분 정도 쉬면 좋으세요?",
                    "",
                    List.of(
                            new OnboardingQuestionOptionResponse("5", "5분", ""),
                            new OnboardingQuestionOptionResponse("10", "10분", ""),
                            new OnboardingQuestionOptionResponse("15", "15분", ""),
                            new OnboardingQuestionOptionResponse("20", "20분", "")
                    )
            ),
            new OnboardingQuestionResponse(
                    "focusInterventionStyle",
                    "일이 밀리면 언제 알려드릴까요?",
                    "",
                    List.of(
                            new OnboardingQuestionOptionResponse("minimal", "거의 안 함", ""),
                            new OnboardingQuestionOptionResponse("balanced", "가끔", ""),
                            new OnboardingQuestionOptionResponse("proactive", "자주", "")
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
            message = "현재 저장된 일정으로 시작합니다.";
        } else if (shouldRefreshBootstrap(profile.getBootstrapCompletedAt())) {
            syncTriggered = true;
            try {
                triggerSilentSync();
                message = "최근 일정을 불러왔습니다.";
            } catch (RuntimeException exception) {
                message = "현재 저장된 일정으로 시작합니다.";
            }
        } else {
            message = "최근 일정으로 시작합니다.";
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
        profile.setFocusSessionMinutes(parseIntegerAnswer(answers, "focusSessionMinutes"));
        profile.setFocusBreakMinutes(parseIntegerAnswer(answers, "focusBreakMinutes"));
        profile.setFocusInterventionStyle(parseChoiceAnswer(answers, "focusInterventionStyle"));
        profile = onboardingProfileRepository.save(profile);

        settingsService.update(new SettingsService.SettingsUpdateRequest(
                profile.getSleepTime(),
                profile.getWakeTime(),
                null,
                null,
                profile.getFocusSessionMinutes(),
                profile.getFocusSessionMinutes(),
                profile.getFocusBreakMinutes(),
                profile.getFocusInterventionStyle(),
                null,
                null,
                null
        ));

        RescheduleSuggestionService.RescheduleSuggestionResponse suggestion = createOnboardingSuggestion(user.getId(), profile);
        ImportedWorkspaceSummaryResponse importSummary = buildImportSummary(user.getId(), isGoogleConnected(user.getId()));
        OnboardingStatusResponse status = buildStatus(user, profile, isGoogleConnected(user.getId()), importSummary, suggestion);

        return new OnboardingAnswersResponse(status, "답변을 저장했습니다.");
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
                    new RescheduleSuggestionService.SuggestionDecisionRequest("온보딩에서 사용자 승인")
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
                        ? "온보딩을 마쳤습니다."
                        : "선택한 시간을 넣고 온보딩을 마쳤습니다."
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

        String workspaceSummary = "일정 %d건, 할 일 %d건이 있습니다."
                .formatted(eventCount, taskCount, scheduleBlockCount);
        String sourceLabel = googleConnected
                ? "최근 일정을 불러왔습니다."
                : "현재 저장된 일정으로 시작합니다.";

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
                formatInteger(profile.getFocusSessionMinutes()),
                formatInteger(profile.getFocusBreakMinutes()),
                profile.getFocusInterventionStyle(),
                formatTime(profile.getSleepTime()),
                formatTime(profile.getWakeTime())
        );
    }

    private boolean hasAnyAnswer(OnboardingProfile profile) {
        return profile.getWakeTime() != null
                || profile.getWorkStartTime() != null
                || profile.getDinnerTime() != null
                || profile.getSleepTime() != null
                || profile.getWeekendStyle() != null
                || profile.getFocusSessionMinutes() != null
                || profile.getFocusBreakMinutes() != null
                || profile.getFocusInterventionStyle() != null;
    }

    private boolean hasRequiredAnswers(OnboardingProfile profile) {
        return profile.getWakeTime() != null
                && profile.getWorkStartTime() != null
                && profile.getDinnerTime() != null
                && profile.getSleepTime() != null
                && profile.getWeekendStyle() != null
                && profile.getFocusSessionMinutes() != null
                && profile.getFocusBreakMinutes() != null
                && profile.getFocusInterventionStyle() != null;
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

    private Integer parseIntegerAnswer(Map<String, String> answers, String questionId) {
        return Integer.valueOf(parseChoiceAnswer(answers, questionId));
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
                    "답변을 저장했습니다.",
                    "지금은 새로 넣을 시간이 없습니다.",
                    List.of(new StructuredAiCommand(
                            AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                            AgentCommandTargetType.NONE.wireValue(),
                            null,
                            Map.of(
                                    "summary", "현재 저장된 일정과 답변을 확인했습니다."
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
                    "처음 넣을 시간을 골랐습니다.",
                    "자주 반복되는 시간을 먼저 넣습니다.",
                    commands
            );
        }

        return rescheduleSuggestionService.createSuggestionFromBatch(
                RescheduleSuggestionTriggerType.ONBOARDING_BOOTSTRAP,
                "처음 넣을 시간을 골랐습니다.",
                "이번 주에 넣을 시간을 골랐습니다.",
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
                    "수면",
                    ScheduleCategory.SLEEP,
                    "잠자는 시간으로 둡니다."
            ));
        }

        LocalTime transitStart = recommendedTransitStart(wakeTime, workStartTime);
        if (transitStart != null) {
            for (DayOfWeek day : WEEKDAYS) {
                blocks.add(new PlannedRoutineBlock(
                        day,
                        transitStart,
                        workStartTime,
                        "출근 준비",
                        ScheduleCategory.TRANSIT,
                        "출근 전에 필요한 시간으로 둡니다."
                ));
            }
        }

        for (DayOfWeek day : WEEKDAYS) {
            blocks.add(new PlannedRoutineBlock(
                    day,
                    dinnerTime,
                    dinnerTime.plusMinutes(60),
                    "저녁",
                    ScheduleCategory.LIFE,
                    "저녁 먹는 시간으로 둡니다."
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
                    "쉬는 시간",
                    ScheduleCategory.LIFE,
                    "주말에 쉬는 시간으로 둡니다."
            );
            case "productive" -> new PlannedRoutineBlock(
                    DayOfWeek.SATURDAY,
                    LocalTime.of(9, 0),
                    LocalTime.of(12, 0),
                    "주말 작업 시간",
                    ScheduleCategory.GROWTH,
                    "주말에 공부나 개인 작업을 할 시간으로 둡니다."
            );
            default -> new PlannedRoutineBlock(
                    DayOfWeek.SATURDAY,
                    LocalTime.of(10, 0),
                    LocalTime.of(12, 0),
                    "개인 작업",
                    ScheduleCategory.GROWTH,
                    "주말에 가볍게 할 일을 할 시간으로 둡니다."
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
                        "category", plan.category().name()
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
            return "답변은 저장됐고, 지금은 새로 넣을 시간이 없습니다.";
        }
        return "지금 넣을 수 있는 시간 %d개를 골랐습니다.".formatted(executableCount);
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

    private String formatInteger(Integer value) {
        return value == null ? null : value.toString();
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
            String focusSessionMinutes,
            String focusBreakMinutes,
            String focusInterventionStyle,
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
