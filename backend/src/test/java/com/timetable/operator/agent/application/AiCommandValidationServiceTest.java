package com.timetable.operator.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-command-validation-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
class AiCommandValidationServiceTest {

    @Autowired
    private AiCommandValidationService validator;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ScheduleBlockRepository scheduleBlockRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private EventRepository eventRepository;

    private AppUser user;
    private ScheduleBlock scheduleBlock;
    private Task task;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        taskRepository.deleteAll();
        scheduleBlockRepository.deleteAll();
        appUserRepository.deleteAll();

        AppUser newUser = new AppUser();
        newUser.setEmail("ai-command-validation@time-table.dev");
        newUser.setDisplayName("AI Validation User");
        newUser.setProvider("local");
        newUser.setDemoUser(true);
        newUser.setTimezone("Asia/Seoul");
        newUser.setAutoRescheduleEnabled(false);
        newUser.setFocusAutoEnterEnabled(false);
        user = appUserRepository.save(newUser);

        ScheduleBlock block = new ScheduleBlock();
        block.setUserId(user.getId());
        block.setDayOfWeek(DayOfWeek.TUESDAY);
        block.setStartTime(LocalTime.of(10, 0));
        block.setEndTime(LocalTime.of(11, 0));
        block.setActivity("기존 회의");
        block.setCategory(ScheduleCategory.WORK);
        block.setSourceType(ScheduleSourceType.MANUAL);
        block.setSourceRef("test");
        scheduleBlock = scheduleBlockRepository.save(block);

        Task newTask = new Task();
        newTask.setUserId(user.getId());
        newTask.setTitle("세금 신고");
        newTask.setDueDate(Instant.parse("2026-05-20T03:00:00Z"));
        newTask.setEstimatedMinutes(30);
        newTask.setActualMinutes(0);
        newTask.setPriority((short) 3);
        newTask.setStatus(TaskStatus.TODO);
        newTask.setSourceType(TaskSourceType.LOCAL);
        task = taskRepository.save(newTask);
    }

    @Test
    void fakeLlmScenarioFixtureCoversTwentyNaturalLanguageCases() {
        List<Scenario> scenarios = List.of(
                new Scenario("수요일 15:00-16:00 회의 추가해줘", true, scheduleCreate("WEDNESDAY", "15:00", "16:00", "회의")),
                new Scenario("매주 월요일 09:00 운동 추가해줘", true, scheduleCreate("MONDAY", "09:00", "10:00", "운동")),
                new Scenario("오늘 할 일에 세금 신고 추가해줘", true, taskCreate("세금 신고")),
                new Scenario("내일 14:00 프로젝트 회의 추가해줘", true, eventCreate("프로젝트 회의", "2026-05-31T05:00:00Z", "2026-05-31T06:00:00Z")),
                new Scenario("오후에 회의 넣어줘", false, scheduleCreateMissing(Map.of("activity", "회의"))),
                new Scenario("밥 먹기 추가해줘", false, scheduleCreateMissing(Map.of("activity", "밥 먹기"))),
                new Scenario("이거 지워줘", false, eventDelete(null)),
                new Scenario("회의 삭제해줘", false, eventDelete(null)),
                new Scenario("<schedule-id> 일정 삭제해줘", true, eventDelete(scheduleBlock.getId().toString())),
                new Scenario("<schedule-id> 일정 30분 미뤄줘", true, eventMove(scheduleBlock.getId().toString(), Map.of("suggestedShiftMinutes", 30))),
                new Scenario("회의 30분 미뤄줘", false, eventMove(null, Map.of("suggestedShiftMinutes", 30))),
                new Scenario("운동 시간을 저녁으로 옮겨줘", false, eventMove(null, Map.of("timeBucket", "evening"))),
                new Scenario("<task-id> 할 일 내일로 미뤄줘", true, taskMove(task.getId().toString(), Map.of("dueDate", "2026-05-31T03:00:00Z"))),
                new Scenario("세금 신고 우선순위 올려줘", false, taskUpdate(null, Map.of("priority", 1))),
                new Scenario("이번 주 일정 다시 맞춰줘", false, requestReschedule()),
                new Scenario("오늘 25시에 회의 추가", false, scheduleCreateMissing(Map.of("dayOfWeek", "FRIDAY", "startTime", "25:00", "endTime", "26:00", "activity", "회의"))),
                new Scenario("다음 주 월요일 오전 회의 준비 추가", false, scheduleCreateMissing(Map.of("dayOfWeek", "MONDAY", "activity", "회의 준비"))),
                new Scenario("구글 동기화해줘", false, syncCommand()),
                new Scenario("아무거나 알아서 정리해줘", false, explainOnly()),
                new Scenario("금요일 10:00-11:00 병원 예약 LIFE 추가", true, scheduleCreate("FRIDAY", "10:00", "11:00", "병원 예약"))
        );

        assertThat(scenarios).hasSize(20);
        for (Scenario scenario : scenarios) {
            StructuredAiCommandBatch resolved = validator.requireExecutableOrClarification(
                    user.getId(),
                    new StructuredAiCommandBatch(scenario.input(), "fake LLM fixture", List.of(scenario.command()))
            );

            assertThat(hasExecutableCommands(resolved))
                    .as(scenario.input())
                    .isEqualTo(scenario.executable());
            if (!scenario.executable()) {
                assertThat(resolved.summary()).as(scenario.input()).isEqualTo("확인이 필요합니다");
                assertThat(resolved.commands().getFirst().payload()).containsEntry("resolutionType", "clarification_required");
            }
        }
    }

    @Test
    void validatorBlocksScheduleCreateConflictsBeforeSuggestionBecomesExecutable() {
        StructuredAiCommandBatch resolved = validator.requireExecutableOrClarification(
                user.getId(),
                new StructuredAiCommandBatch("충돌 일정", "fake", List.of(scheduleCreate("TUESDAY", "10:30", "11:30", "겹치는 회의")))
        );

        assertThat(hasExecutableCommands(resolved)).isFalse();
        assertThat(resolved.summary()).isEqualTo("확인이 필요합니다");
        assertThat(resolved.commands().getFirst().payload()).containsEntry("reason", "invalid_schedule_create_fields");
    }

    private boolean hasExecutableCommands(StructuredAiCommandBatch batch) {
        return batch.commands().stream().anyMatch(StructuredAiCommand::requiresConfirmation);
    }

    private static StructuredAiCommand scheduleCreate(String day, String start, String end, String activity) {
        return command(AgentCommandActionType.CREATE_EVENT, AgentCommandTargetType.EVENT, null, Map.of(
                "dayOfWeek", day,
                "startTime", start,
                "endTime", end,
                "activity", activity,
                "category", "LIFE"
        ));
    }

    private static StructuredAiCommand scheduleCreateMissing(Map<String, Object> payload) {
        return command(AgentCommandActionType.CREATE_EVENT, AgentCommandTargetType.EVENT, null, payload);
    }

    private static StructuredAiCommand eventCreate(String title, String startAt, String endAt) {
        return command(AgentCommandActionType.CREATE_EVENT, AgentCommandTargetType.EVENT, null, Map.of(
                "title", title,
                "startAt", startAt,
                "endAt", endAt,
                "category", "WORK"
        ));
    }

    private static StructuredAiCommand eventDelete(String targetId) {
        return command(AgentCommandActionType.DELETE_EVENT, AgentCommandTargetType.EVENT, targetId, Map.of());
    }

    private static StructuredAiCommand eventMove(String targetId, Map<String, Object> payload) {
        return command(AgentCommandActionType.MOVE_EVENT, AgentCommandTargetType.EVENT, targetId, payload);
    }

    private static StructuredAiCommand taskCreate(String title) {
        return command(AgentCommandActionType.RECOMMEND_TASK, AgentCommandTargetType.TASK, null, Map.of("title", title));
    }

    private static StructuredAiCommand taskMove(String targetId, Map<String, Object> payload) {
        return command(AgentCommandActionType.MOVE_EVENT, AgentCommandTargetType.TASK, targetId, payload);
    }

    private static StructuredAiCommand taskUpdate(String targetId, Map<String, Object> payload) {
        return command(AgentCommandActionType.UPDATE_EVENT, AgentCommandTargetType.TASK, targetId, payload);
    }

    private static StructuredAiCommand requestReschedule() {
        return command(AgentCommandActionType.REQUEST_RESCHEDULE, AgentCommandTargetType.EVENT, null, Map.of());
    }

    private static StructuredAiCommand syncCommand() {
        return new StructuredAiCommand(
                AgentCommandActionType.RUN_SYNC.wireValue(),
                AgentCommandTargetType.SYNC.wireValue(),
                null,
                Map.of("targetSystem", "googleCalendar"),
                "sync fixture",
                false
        );
    }

    private static StructuredAiCommand explainOnly() {
        return new StructuredAiCommand(
                AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                AgentCommandTargetType.NONE.wireValue(),
                null,
                Map.of("summary", "unsupported"),
                "unsupported fixture",
                false
        );
    }

    private static StructuredAiCommand command(
            AgentCommandActionType actionType,
            AgentCommandTargetType targetType,
            String targetId,
            Map<String, Object> payload
    ) {
        return new StructuredAiCommand(
                actionType.wireValue(),
                targetType.wireValue(),
                targetId,
                payload,
                "fake LLM scripted response",
                true
        );
    }

    private record Scenario(String input, boolean executable, StructuredAiCommand command) {
    }
}
