package com.timetable.operator.agent.application.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.timetable.operator.agent.application.AiAgentRequest;
import com.timetable.operator.agent.application.AiRescheduleClient;
import com.timetable.operator.auth.domain.AppUser;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiContextPackageBuilderTest {

    private final AiContextPackageBuilder builder = new AiContextPackageBuilder();

    @Test
    void leaveAdjustmentIncludesRoutineAndTasksButOmitsAvailabilityWhenNotUseful() {
        ContextPackage contextPackage = builder.build(request(
                "오늘 내일 연차를 썼다. 일정을 수정해라.",
                context(
                        List.of(event("제품 회의", "WORK")),
                        List.of(block("MONDAY", "09:00", "18:00", "근무", "WORK")),
                        List.of(task("보고서", "WORK")),
                        List.of(history("REJECTED", "어제 요청", "보류"), history("DRAFT", "다른 요청", "초안")),
                        List.of(window("오전 후보"))
                )
        ));

        assertThat(contextPackage.requestType()).isEqualTo("leave_adjustment");
        assertThat(sectionNames(contextPackage.includedSections())).contains("events", "weeklyBlocks", "tasks");
        assertThat(sectionNames(contextPackage.excludedSections())).contains("messageHistory", "availabilityWindows");
        assertThat(contextPackage.privacyExposureScore()).isBetween(1, 10);
        assertThat(contextPackage.characterEstimate()).isPositive();
    }

    @Test
    void availabilityRequestIncludesOnlyBoundedCandidateWindows() {
        ContextPackage contextPackage = builder.build(request(
                "이번주안에 병원 아무때나 넣어줘",
                context(
                        List.of(),
                        List.of(block("MONDAY", "09:00", "18:00", "근무", "WORK")),
                        List.of(),
                        List.of(),
                        List.of(window("월 오전"), window("화 오후"), window("수 오전"), window("목 오전"))
                )
        ));

        assertThat(contextPackage.requestType()).isEqualTo("availability_candidate");
        ContextPackage.ContextSection windows = contextPackage.includedSections().stream()
                .filter(section -> section.name().equals("availabilityWindows"))
                .findFirst()
                .orElseThrow();
        assertThat(windows.itemCount()).isEqualTo(3);
    }

    private List<String> sectionNames(List<ContextPackage.ContextSection> sections) {
        return sections.stream().map(ContextPackage.ContextSection::name).toList();
    }

    private AiAgentRequest request(String reason, AiRescheduleClient.RescheduleAiContext context) {
        AppUser user = new AppUser();
        user.setTimezone("Asia/Seoul");
        return new AiAgentRequest(user, reason, context);
    }

    private AiRescheduleClient.RescheduleAiContext context(
            List<AiRescheduleClient.EventContext> events,
            List<AiRescheduleClient.ScheduleBlockContext> blocks,
            List<AiRescheduleClient.TaskContext> tasks,
            List<AiRescheduleClient.MessageHistoryContext> history,
            List<AiRescheduleClient.AvailabilityWindowContext> windows
    ) {
        return new AiRescheduleClient.RescheduleAiContext(
                new AiRescheduleClient.UserContext("user-1", "Asia/Seoul"),
                new AiRescheduleClient.RequestContext("manual_request", "test", null, null, "2026-06-05T00:00:00Z", "2026-06-06T00:00:00Z"),
                blocks,
                events,
                tasks,
                history,
                windows
        );
    }

    private AiRescheduleClient.EventContext event(String title, String category) {
        return new AiRescheduleClient.EventContext("event-1", title, "sensitive detail", category, "2026-06-05T01:00:00Z", "2026-06-05T02:00:00Z", "PLANNED", "LOCAL_ONLY");
    }

    private AiRescheduleClient.ScheduleBlockContext block(String day, String start, String end, String activity, String category) {
        return new AiRescheduleClient.ScheduleBlockContext("block-1", day, start, end, activity, category, "sensitive note");
    }

    private AiRescheduleClient.TaskContext task(String title, String category) {
        return new AiRescheduleClient.TaskContext("task-1", title, "sensitive task", category, null, 60, 0, (short) 3, "TODO", "LOCAL_ONLY");
    }

    private AiRescheduleClient.MessageHistoryContext history(String status, String request, String summary) {
        return new AiRescheduleClient.MessageHistoryContext("2026-06-05T00:00:00Z", status, request, summary, null);
    }

    private AiRescheduleClient.AvailabilityWindowContext window(String label) {
        return new AiRescheduleClient.AvailabilityWindowContext("2026-06-05T01:00:00Z", "2026-06-05T02:00:00Z", label, 60, "test");
    }
}
