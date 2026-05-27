package com.timetable.operator.schedule.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:schedule-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "app.ai.enabled=false"
})
@AutoConfigureMockMvc
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void manualBlockCrudFlowWorks() throws Exception {
        String initialPayload = """
                {
                  "dayOfWeek": "MONDAY",
                  "startTime": "09:00",
                  "endTime": "10:30",
                  "activity": "테스트 집중 블록",
                  "category": "WORK",
                  "note": "초기 메모"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/schedule/blocks")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity").value("테스트 집중 블록"))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"))
                .andReturn();

        String blockId = createResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/schedule/week").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("테스트 집중 블록")));

        String updatePayload = """
                {
                  "dayOfWeek": "MONDAY",
                  "startTime": "10:00",
                  "endTime": "11:00",
                  "activity": "수정된 집중 블록",
                  "category": "GROWTH",
                  "note": "수정 메모"
                }
                """;

        mockMvc.perform(put("/api/schedule/blocks/{blockId}", blockId)
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity").value("수정된 집중 블록"))
                .andExpect(jsonPath("$.category").value("GROWTH"))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"));

        mockMvc.perform(delete("/api/schedule/blocks/{blockId}", blockId)
                        .with(user("tester").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/schedule/week").with(user("tester").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("수정된 집중 블록"))));
    }

    @Test
    void invalidEnumAndTooShortBlockReturnBadRequestJson() throws Exception {
        mockMvc.perform(post("/api/schedule/blocks")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dayOfWeek": "NOT_A_DAY",
                                  "startTime": "09:00",
                                  "endTime": "10:00",
                                  "activity": "잘못된 요일",
                                  "category": "WORK"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/api/schedule/blocks")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dayOfWeek": "MONDAY",
                                  "startTime": "09:00",
                                  "endTime": "09:01",
                                  "activity": "너무 짧은 블록",
                                  "category": "WORK"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("최소 15분")));
    }

    @Test
    void oversizedManualBlockTextReturnsBadRequestJson() throws Exception {
        String oversizedActivity = "긴 활동".repeat(90);
        String oversizedNote = "긴 메모".repeat(260);

        mockMvc.perform(post("/api/schedule/blocks")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dayOfWeek": "MONDAY",
                                  "startTime": "09:00",
                                  "endTime": "10:00",
                                  "activity": "%s",
                                  "category": "WORK",
                                  "note": "정상 메모"
                                }
                                """.formatted(oversizedActivity)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/api/schedule/blocks")
                        .with(user("tester").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dayOfWeek": "MONDAY",
                                  "startTime": "10:30",
                                  "endTime": "11:00",
                                  "activity": "정상 활동",
                                  "category": "WORK",
                                  "note": "%s"
                                }
                                """.formatted(oversizedNote)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
