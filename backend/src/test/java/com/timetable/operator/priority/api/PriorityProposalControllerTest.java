package com.timetable.operator.priority.api;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:priority-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
@AutoConfigureMockMvc
class PriorityProposalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

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
        appUserRepository.save(user);
    }

    @Test
    void priorityProposalAcceptAndRejectEndpointsWork() throws Exception {
        String firstTargetId = UUID.randomUUID().toString();
        MvcResult firstChatResult = mockMvc.perform(post("/api/chat/command")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "%s 우선순위 1로 올려줘"
                                }
                                """.formatted(firstTargetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions[0].priorityProposalId", notNullValue()))
                .andReturn();

        String firstProposalId = firstChatResult.getResponse().getContentAsString()
                .replaceAll(".*\"priorityProposalId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/priority/proposals/{proposalId}/accept", firstProposalId)
                        .with(user("tester").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("accepted"));

        String secondTargetId = UUID.randomUUID().toString();
        MvcResult secondChatResult = mockMvc.perform(post("/api/chat/command")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "%s 우선순위 4로 낮춰줘"
                                }
                                """.formatted(secondTargetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions[0].priorityProposalId", notNullValue()))
                .andReturn();

        String secondProposalId = secondChatResult.getResponse().getContentAsString()
                .replaceAll(".*\"priorityProposalId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/priority/proposals/{proposalId}/reject", secondProposalId)
                        .with(user("tester").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rejected"));

        mockMvc.perform(get("/api/priority/proposals").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("rejected"))
                .andExpect(jsonPath("$.data[1].status").value("accepted"));
    }
}
