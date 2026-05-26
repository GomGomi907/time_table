package com.timetable.operator.focus.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.agent.infrastructure.RescheduleSuggestionRepository;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.focus.infrastructure.FocusSessionLogRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.settings.domain.UserPreferences;
import com.timetable.operator.settings.infrastructure.UserPreferencesRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:focus-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "app.ai.enabled=false"
})
@AutoConfigureMockMvc
class FocusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private FocusSessionLogRepository focusSessionLogRepository;

    @Autowired
    private ScheduleBlockRepository scheduleBlockRepository;

    @Autowired
    private UserPreferencesRepository userPreferencesRepository;

    @Autowired
    private RescheduleSuggestionRepository rescheduleSuggestionRepository;

    private AppUser user;

    @BeforeEach
    void setUp() {
        focusSessionLogRepository.deleteAll();
        scheduleBlockRepository.deleteAll();
        taskRepository.deleteAll();
        eventRepository.deleteAll();
        userPreferencesRepository.deleteAll();
        rescheduleSuggestionRepository.deleteAll();

        user = appUserRepository.findByEmail("local@time-table.dev")
                .orElseGet(() -> {
                    AppUser appUser = new AppUser();
                    appUser.setEmail("local@time-table.dev");
                    appUser.setDisplayName("Local User");
                    appUser.setProvider("local");
                    appUser.setDemoUser(true);
                    appUser.setTimezone("Asia/Seoul");
                    return appUserRepository.save(appUser);
                });
    }

    @Test
    void startRecommendedTaskPromotesTaskToCurrentFocus() throws Exception {
        Task task = new Task();
        task.setUserId(user.getId());
        task.setTitle("추천 할 일");
        task.setEstimatedMinutes(45);
        task.setPriority((short) 1);
        task.setStatus(TaskStatus.TODO);
        task.setSourceType(TaskSourceType.LOCAL);
        task.setSyncState(PlannerSyncState.LOCAL_ONLY);
        task = taskRepository.save(task);

        mockMvc.perform(post("/api/focus/current/start-recommended-task")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "%s"
                                }
                                """.formatted(task.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusState").value("ACTIVE_TASK"))
                .andExpect(jsonPath("$.data.currentItem.type").value("task"))
                .andExpect(jsonPath("$.data.currentItem.title").value("추천 할 일"));

        mockMvc.perform(get("/api/focus/current").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusState").value("ACTIVE_TASK"))
                .andExpect(jsonPath("$.data.currentItem.type").value("task"));
    }

    @Test
    void currentFocusIncludesEventThatStartedBeforeToday() throws Exception {
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        Instant dayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("야간 운영");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(dayStart.minus(Duration.ofHours(1)));
        event.setEndAt(dayStart.plus(Duration.ofHours(26)));
        event.setPriority((short) 1);
        event.setStatus(EventStatus.ACTIVE);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.LOCAL_ONLY);
        eventRepository.save(event);

        mockMvc.perform(get("/api/focus/current").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusState").value("ACTIVE_EVENT"))
                .andExpect(jsonPath("$.data.currentItem.title").value("야간 운영"));
    }

    @Test
    void currentFocusIncludesScheduleContextWhenOnlyRoutineBlocksExist() throws Exception {
        DayOfWeek tomorrow = LocalDate.now(ZoneId.of(user.getTimezone())).plusDays(1).getDayOfWeek();

        ScheduleBlock block = new ScheduleBlock();
        block.setUserId(user.getId());
        block.setDayOfWeek(tomorrow);
        block.setStartTime(LocalTime.of(9, 0));
        block.setEndTime(LocalTime.of(10, 0));
        block.setActivity("내일 집중 루틴");
        block.setCategory(ScheduleCategory.GROWTH);
        block.setSourceType(ScheduleSourceType.MANUAL);
        block.setSourceRef("test");
        scheduleBlockRepository.save(block);

        mockMvc.perform(get("/api/focus/current").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusState").value("NO_ACTIVE_ITEM"))
                .andExpect(jsonPath("$.data.scheduleContext.state").value("UPCOMING_BLOCK"))
                .andExpect(jsonPath("$.data.scheduleContext.nextBlock.activity").value("내일 집중 루틴"));
    }

    @Test
    void currentFocusIncludesDbBackedPreferenceContext() throws Exception {
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(user.getId());
        preferences.setQuietHoursStart(LocalTime.of(23, 0));
        preferences.setQuietHoursEnd(LocalTime.of(7, 0));
        preferences.setBufferMinutes(10);
        preferences.setOvertimeTriggerMinutes(15);
        preferences.setOpenGapTriggerMinutes(60);
        preferences.setPreferredFocusMinutes(60);
        preferences.setBreakBufferMinutes(15);
        preferences.setInterventionFrequency("minimal");
        userPreferencesRepository.save(preferences);

        mockMvc.perform(get("/api/focus/current").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preferenceContext.preferredFocusMinutes").value(60))
                .andExpect(jsonPath("$.data.preferenceContext.breakBufferMinutes").value(15))
                .andExpect(jsonPath("$.data.preferenceContext.interventionStyle").value("minimal"))
                .andExpect(jsonPath("$.data.preferenceContext.interventionLabel").value("최소 개입"));
    }

    @Test
    void currentFocusDeduplicatesRecommendedTasksByUserVisibleTitle() throws Exception {
        Task first = new Task();
        first.setUserId(user.getId());
        first.setTitle("오늘 할 일 정리");
        first.setEstimatedMinutes(30);
        first.setPriority((short) 1);
        first.setDueDate(Instant.now().plus(Duration.ofHours(1)));
        first.setStatus(TaskStatus.TODO);
        first.setSourceType(TaskSourceType.GOOGLE_TASKS);
        first.setSyncState(PlannerSyncState.IMPORTED);
        taskRepository.save(first);

        Task duplicate = new Task();
        duplicate.setUserId(user.getId());
        duplicate.setTitle(" 오늘   할 일 정리 ");
        duplicate.setEstimatedMinutes(30);
        duplicate.setPriority((short) 2);
        duplicate.setDueDate(Instant.now().plus(Duration.ofHours(2)));
        duplicate.setStatus(TaskStatus.TODO);
        duplicate.setSourceType(TaskSourceType.GOOGLE_TASKS);
        duplicate.setSyncState(PlannerSyncState.IMPORTED);
        taskRepository.save(duplicate);

        Task distinct = new Task();
        distinct.setUserId(user.getId());
        distinct.setTitle("다른 할 일");
        distinct.setEstimatedMinutes(45);
        distinct.setPriority((short) 3);
        distinct.setDueDate(Instant.now().plus(Duration.ofHours(3)));
        distinct.setStatus(TaskStatus.TODO);
        distinct.setSourceType(TaskSourceType.LOCAL);
        distinct.setSyncState(PlannerSyncState.LOCAL_ONLY);
        taskRepository.save(distinct);

        mockMvc.perform(get("/api/focus/current").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendedTasks", hasSize(2)))
                .andExpect(jsonPath("$.data.recommendedTasks[*].title", contains("오늘 할 일 정리", "다른 할 일")));
    }

    @Test
    void postponeWithAiRescheduleRequestCreatesPendingSuggestion() throws Exception {
        Instant now = Instant.now();
        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("미룰 집중 일정");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(now.minus(Duration.ofMinutes(10)));
        event.setEndAt(now.plus(Duration.ofMinutes(40)));
        event.setPriority((short) 1);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.LOCAL_ONLY);
        event = eventRepository.save(event);

        mockMvc.perform(post("/api/focus/current/postpone")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemType": "event",
                                  "itemId": "%s",
                                  "reason": "회의가 길어졌습니다.",
                                  "requestAiReschedule": true
                                }
                                """.formatted(event.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusState").exists());

        mockMvc.perform(get("/api/agent/suggestions").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("pending"))
                .andExpect(jsonPath("$.data[0].triggerType").value("postpone"));
    }

    @Test
    void completeActiveScheduleBlockShortensCurrentBlock() throws Exception {
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        LocalTime now = LocalTime.now(zoneId).withSecond(0).withNano(0);

        ScheduleBlock block = new ScheduleBlock();
        block.setUserId(user.getId());
        block.setDayOfWeek(LocalDate.now(zoneId).getDayOfWeek());
        block.setStartTime(now.minusMinutes(30));
        block.setEndTime(now.plusMinutes(45));
        block.setActivity("완료할 현재 루틴");
        block.setCategory(ScheduleCategory.WORK);
        block.setSourceType(ScheduleSourceType.MANUAL);
        block.setSourceRef("test");
        block = scheduleBlockRepository.save(block);

        mockMvc.perform(post("/api/focus/current/schedule-blocks/{blockId}/complete", block.getId())
                        .with(user("tester").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusState").exists());

        ScheduleBlock completed = scheduleBlockRepository.findById(block.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(completed.getEndTime()).isEqualTo(now);
        org.assertj.core.api.Assertions.assertThat(completed.getSourceRef()).isEqualTo("focus-complete");
    }

    @Test
    void postponeScheduleBlockShiftsBlockAndCreatesPendingSuggestion() throws Exception {
        DayOfWeek tomorrow = LocalDate.now(ZoneId.of(user.getTimezone())).plusDays(1).getDayOfWeek();

        ScheduleBlock block = new ScheduleBlock();
        block.setUserId(user.getId());
        block.setDayOfWeek(tomorrow);
        block.setStartTime(LocalTime.of(9, 0));
        block.setEndTime(LocalTime.of(10, 0));
        block.setActivity("미룰 루틴");
        block.setCategory(ScheduleCategory.WORK);
        block.setSourceType(ScheduleSourceType.MANUAL);
        block.setSourceRef("test");
        block = scheduleBlockRepository.save(block);

        mockMvc.perform(post("/api/focus/current/schedule-blocks/{blockId}/postpone", block.getId())
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "사용자가 실행 모드에서 미루기를 눌렀습니다.",
                                  "requestAiReschedule": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.focusState").exists());

        ScheduleBlock postponed = scheduleBlockRepository.findById(block.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(postponed.getStartTime()).isEqualTo(LocalTime.of(9, 30));
        org.assertj.core.api.Assertions.assertThat(postponed.getEndTime()).isEqualTo(LocalTime.of(10, 30));
        org.assertj.core.api.Assertions.assertThat(postponed.getSourceRef()).isEqualTo("focus-postpone");

        mockMvc.perform(get("/api/agent/suggestions").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("pending"))
                .andExpect(jsonPath("$.data[0].triggerType").value("postpone"));
    }
}
