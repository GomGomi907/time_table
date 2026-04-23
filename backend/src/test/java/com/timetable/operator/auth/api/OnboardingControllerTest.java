package com.timetable.operator.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.agent.infrastructure.RescheduleSuggestionRepository;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.auth.infrastructure.OnboardingProfileRepository;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboarding-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
@AutoConfigureMockMvc
class OnboardingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private OnboardingProfileRepository onboardingProfileRepository;

    @Autowired
    private RescheduleSuggestionRepository rescheduleSuggestionRepository;

    @Autowired
    private ScheduleBlockRepository scheduleBlockRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TaskRepository taskRepository;

    private AppUser user;

    @BeforeEach
    void setUp() {
        rescheduleSuggestionRepository.deleteAll();
        onboardingProfileRepository.deleteAll();
        scheduleBlockRepository.deleteAll();
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
                    appUser.setAutoRescheduleEnabled(true);
                    appUser.setFocusAutoEnterEnabled(false);
                    return appUserRepository.save(appUser);
                });

        Event event = new Event();
        event.setUserId(user.getId());
        event.setTitle("기존 회의");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(Instant.now().plusSeconds(3_600));
        event.setEndAt(Instant.now().plusSeconds(7_200));
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.LOCAL);
        eventRepository.save(event);

        Task task = new Task();
        task.setUserId(user.getId());
        task.setTitle("기존 태스크");
        task.setEstimatedMinutes(45);
        task.setStatus(TaskStatus.TODO);
        task.setSourceType(TaskSourceType.LOCAL);
        taskRepository.save(task);
    }

    @Test
    void statusStartsAtBootstrapStepAndExposesQuestions() throws Exception {
        mockMvc.perform(get("/api/onboarding/status").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextStep").value("bootstrap"))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.questions.length()").value(5))
                .andExpect(jsonPath("$.importSummary.calendarEventCount").value(1))
                .andExpect(jsonPath("$.importSummary.taskCount").value(1));
    }

    @Test
    void answersCanGenerateSuggestionAndCompletionAppliesBlocks() throws Exception {
        mockMvc.perform(post("/api/onboarding/bootstrap")
                        .with(user("tester").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.nextStep").value("questions"));

        MvcResult answersResult = mockMvc.perform(post("/api/onboarding/answers")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": {
                                    "wakeTime": "07:00",
                                    "workStartTime": "09:00",
                                    "dinnerTime": "19:00",
                                    "sleepTime": "23:30",
                                    "weekendStyle": "balanced"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.profileReady").value(true))
                .andExpect(jsonPath("$.status.aiExperienceReady").value(true))
                .andExpect(jsonPath("$.status.experience.previewItems.length()").value(4))
                .andReturn();

        String suggestionId = answersResult.getResponse().getContentAsString()
                .replaceAll(".*\"suggestion\":\\{\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/onboarding/complete")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applySuggestion": true,
                                  "suggestionId": "%s"
                                }
                                """.formatted(suggestionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.completed").value(true))
                .andExpect(jsonPath("$.appliedSuggestion.status").value("applied"));

        assertThat(scheduleBlockRepository.countByUserId(user.getId())).isGreaterThan(0);
    }
}
