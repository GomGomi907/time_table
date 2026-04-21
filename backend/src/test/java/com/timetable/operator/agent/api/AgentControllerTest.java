package com.timetable.operator.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
@AutoConfigureMockMvc
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ScheduleBlockRepository scheduleBlockRepository;

    private ScheduleBlock savedBlock;

    @BeforeEach
    void setUp() {
        AppUser user = appUserRepository.findByEmail("local@time-table.dev")
                .orElseGet(() -> {
                    AppUser newUser = new AppUser();
                    newUser.setEmail("local@time-table.dev");
                    newUser.setDisplayName("Local User");
                    newUser.setProvider("local");
                    newUser.setDemoUser(true);
                    newUser.setTimezone("Asia/Seoul");
                    newUser.setAutoRescheduleEnabled(false);
                    newUser.setFocusAutoEnterEnabled(false);
                    return appUserRepository.save(newUser);
                });

        if (scheduleBlockRepository.findByUserId(user.getId()).isEmpty()) {
            ScheduleBlock block = new ScheduleBlock();
            block.setUserId(user.getId());
            block.setDayOfWeek(DayOfWeek.MONDAY);
            block.setStartTime(LocalTime.of(9, 0));
            block.setEndTime(LocalTime.of(10, 0));
            block.setActivity("테스트 일정");
            block.setCategory(ScheduleCategory.WORK);
            block.setNote("agent test");
            block.setSourceType(ScheduleSourceType.MANUAL);
            block.setSourceRef("test-seed");
            savedBlock = scheduleBlockRepository.save(block);
        } else {
            savedBlock = scheduleBlockRepository.findByUserId(user.getId()).getFirst();
        }
    }

    @Test
    void chatCommandCreatesSuggestionAndApplyRevertFlowRestoresScheduleBlock() throws Exception {
        MvcResult chatResult = mockMvc.perform(post("/api/chat/command")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "%s 일정 30분 미뤄줘"
                                }
                                """.formatted(savedBlock.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent").value("multi_command_reschedule"))
                .andExpect(jsonPath("$.data.actions[0].result").value("suggestion_created"))
                .andExpect(jsonPath("$.data.actions[0].suggestionId", notNullValue()))
                .andReturn();

        String suggestionId = chatResult.getResponse().getContentAsString()
                .replaceAll(".*\"suggestionId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/apply", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "사용자 승인"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("applied"));

        ScheduleBlock shiftedBlock = scheduleBlockRepository.findById(savedBlock.getId()).orElseThrow();
        assertThat(shiftedBlock.getStartTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(shiftedBlock.getEndTime()).isEqualTo(LocalTime.of(10, 30));

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/revert", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "되돌리기 검증"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("reverted"));

        ScheduleBlock revertedBlock = scheduleBlockRepository.findById(savedBlock.getId()).orElseThrow();
        assertThat(revertedBlock.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(revertedBlock.getEndTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void manualSuggestionCanBeRejected() throws Exception {
        MvcResult suggestionResult = mockMvc.perform(post("/api/agent/reschedule")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "triggerType": "manual_request",
                                  "reason": "이번 주 일정 전체 다시 맞춰줘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andReturn();

        String suggestionId = suggestionResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/agent/suggestions/{suggestionId}/reject", suggestionId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "지금은 수동으로 처리"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rejected"));

        mockMvc.perform(get("/api/agent/suggestions").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("rejected"));
    }
}
