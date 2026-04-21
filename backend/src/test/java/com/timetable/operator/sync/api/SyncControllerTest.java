package com.timetable.operator.sync.api;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sync-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
@AutoConfigureMockMvc
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CalendarConnectionRepository calendarConnectionRepository;

    @BeforeEach
    void setUp() {
        if (appUserRepository.findByEmail("local@time-table.dev").isPresent()) {
            return;
        }

        AppUser user = new AppUser();
        user.setEmail("local@time-table.dev");
        user.setDisplayName("Local User");
        user.setProvider("local");
        user.setDemoUser(true);
        user.setTimezone("Asia/Seoul");
        user.setAutoRescheduleEnabled(false);
        user.setFocusAutoEnterEnabled(false);
        AppUser savedUser = appUserRepository.save(user);

        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(savedUser.getId());
        connection.setProvider("google");
        connection.setStatus(CalendarConnectionStatus.CONNECTED);
        connection.setEmail(savedUser.getEmail());
        calendarConnectionRepository.save(connection);
    }

    @Test
    void syncStatusWebhookAndConflictResolutionFlowWorks() throws Exception {
        mockMvc.perform(post("/api/sync/google/calendar")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "inbound",
                                  "resolvePolicy": "proposal_first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetSystem").value("google_calendar"))
                .andExpect(jsonPath("$.data.status").value("success"));

        mockMvc.perform(get("/api/sync/status").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.googleCalendar.status").value("success"))
                .andExpect(jsonPath("$.meta.webhookTarget").value("google_calendar"))
                .andExpect(jsonPath("$.meta.pollingTarget").value("google_tasks"));

        MvcResult webhookResult = mockMvc.perform(post("/api/sync/google/calendar/webhook")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .header("X-Goog-Channel-Id", "channel-123")
                        .header("X-Goog-Resource-Id", "resource-456")
                        .header("X-Goog-Resource-State", "exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncRunId", notNullValue()))
                .andExpect(jsonPath("$.data.conflictId", notNullValue()))
                .andReturn();

        String conflictId = webhookResult.getResponse().getContentAsString()
                .replaceAll(".*\"conflictId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/sync/conflicts/{conflictId}/resolve", conflictId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resolution": "fork_local"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andExpect(jsonPath("$.data.resolution").value("fork_local"));
    }
}
