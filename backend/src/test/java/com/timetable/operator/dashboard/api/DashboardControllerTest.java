package com.timetable.operator.dashboard.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.agent.infrastructure.RescheduleSuggestionRepository;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.goals.infrastructure.GoalRepository;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboard-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
@AutoConfigureMockMvc
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ScheduleBlockRepository scheduleBlockRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private RescheduleSuggestionRepository rescheduleSuggestionRepository;

    @BeforeEach
    void setUp() {
        rescheduleSuggestionRepository.deleteAll();
        scheduleBlockRepository.deleteAll();
        goalRepository.deleteAll();
        taskRepository.deleteAll();
        eventRepository.deleteAll();

        appUserRepository.findByEmail("local@time-table.dev")
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
    }

    @Test
    void summaryReturnsDashboardContractAndSeedsCoreWorkspaceData() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.week.week.length()").value(7))
                .andExpect(jsonPath("$.data.goals.length()").value(3))
                .andExpect(jsonPath("$.data.metrics.averageGoalProgress").value(35))
                .andExpect(jsonPath("$.data.metrics.topGoal.title").value("주 3회 운동"))
                .andExpect(jsonPath("$.data.focus.focusState").value("NO_ACTIVE_ITEM"))
                .andExpect(jsonPath("$.data.sync.data.googleCalendar.status").value("not_connected"))
                .andExpect(jsonPath("$.data.sync.meta.adapterMode").value("read_only"))
                .andExpect(jsonPath("$.data.sync.meta.externalReadEnabled").value(true))
                .andExpect(jsonPath("$.data.sync.meta.pendingConflictCount").value(0))
                .andExpect(jsonPath("$.data.suggestions.length()").value(0));
    }
}
