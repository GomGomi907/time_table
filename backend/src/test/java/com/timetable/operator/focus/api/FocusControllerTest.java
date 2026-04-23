package com.timetable.operator.focus.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.focus.infrastructure.FocusSessionLogRepository;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

    private AppUser user;

    @BeforeEach
    void setUp() {
        focusSessionLogRepository.deleteAll();
        taskRepository.deleteAll();
        eventRepository.deleteAll();

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
        task.setTitle("추천 태스크");
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
                .andExpect(jsonPath("$.data.currentItem.title").value("추천 태스크"));

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
}
