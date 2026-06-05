package com.timetable.operator.agent.application.context;

import com.timetable.operator.agent.application.AiAgentRequest;
import com.timetable.operator.agent.application.AiRescheduleClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AiContextPackageBuilder {

    public ContextPackage build(AiAgentRequest request) {
        AiRescheduleClient.RescheduleAiContext context = request.context();
        String reason = request.reason() == null ? "" : request.reason();
        String timezone = request.user() == null ? "Asia/Seoul" : request.user().getTimezone();
        return build(reason, timezone, context);
    }

    public ContextPackage build(AiRescheduleClient.RescheduleAiContext context) {
        String reason = context == null || context.request() == null || context.request().reason() == null
                ? ""
                : context.request().reason();
        String timezone = context == null || context.user() == null || context.user().timezone() == null
                ? "Asia/Seoul"
                : context.user().timezone();
        return build(reason, timezone, context);
    }

    private ContextPackage build(String reason, String timezone, AiRescheduleClient.RescheduleAiContext context) {
        String requestType = classify(reason);
        List<ContextPackage.ContextSection> included = new ArrayList<>();
        List<ContextPackage.ContextSection> excluded = new ArrayList<>();
        int estimate = safeLength(reason);
        int privacy = 0;

        if (context != null) {
            int eventCount = size(context.events());
            if (eventCount > 0) {
                included.add(new ContextPackage.ContextSection("events", "request needs schedule conflict/protection context", eventCount, true));
                estimate += eventCount * 160;
                privacy += 2;
            } else {
                excluded.add(new ContextPackage.ContextSection("events", "no events in selected scope", 0, false));
            }

            int blockCount = size(context.weeklyBlocks());
            if (blockCount > 0 && needsRoutineContext(requestType)) {
                included.add(new ContextPackage.ContextSection("weeklyBlocks", "routine/workload reasoning is relevant", blockCount, true));
                estimate += blockCount * 120;
                privacy += 1;
            } else {
                excluded.add(new ContextPackage.ContextSection("weeklyBlocks", "not needed for request type", blockCount, false));
            }

            int taskCount = size(context.tasks());
            if (taskCount > 0 && needsWorkloadContext(requestType)) {
                included.add(new ContextPackage.ContextSection("tasks", "workload context is relevant", taskCount, true));
                estimate += taskCount * 100;
                privacy += 1;
            } else {
                excluded.add(new ContextPackage.ContextSection("tasks", "not needed for request type", taskCount, false));
            }

            int historyCount = size(context.messageHistory());
            if (historyCount == 1 || requestType.equals("follow_up")) {
                included.add(new ContextPackage.ContextSection("messageHistory", "single follow-up anchor only", Math.min(historyCount, 1), true));
                estimate += Math.min(historyCount, 1) * 180;
                privacy += historyCount > 0 ? 1 : 0;
            } else {
                excluded.add(new ContextPackage.ContextSection("messageHistory", "omitted unless needed for follow-up disambiguation", historyCount, false));
            }

            int windowCount = size(context.availabilityWindows());
            if (windowCount > 0 && requestType.equals("availability_candidate")) {
                included.add(new ContextPackage.ContextSection("availabilityWindows", "candidate-time request", Math.min(windowCount, 3), false));
                estimate += Math.min(windowCount, 3) * 80;
            } else {
                excluded.add(new ContextPackage.ContextSection("availabilityWindows", "omitted unless candidate-time selection is useful", windowCount, false));
            }
        }

        ContextPackage.TemporalScope temporalScope = new ContextPackage.TemporalScope(
                context == null || context.request() == null ? null : context.request().resolvedRangeStart(),
                context == null || context.request() == null ? null : context.request().resolvedRangeEnd(),
                timezone == null || timezone.isBlank() ? "Asia/Seoul" : timezone
        );
        return new ContextPackage(requestType, temporalScope, List.copyOf(included), List.copyOf(excluded), estimate, Math.min(10, privacy));
    }

    private String classify(String reason) {
        String text = reason.toLowerCase().replaceAll("\\s+", "");
        if (text.contains("연차") || text.contains("휴가") || text.contains("반차") || text.contains("병가") || text.contains("아파")) {
            return "leave_adjustment";
        }
        if (text.contains("출장")) {
            return "travel_day";
        }
        if (text.contains("다지워") || text.contains("삭제") || text.contains("deleteall")) {
            return "destructive_bulk";
        }
        if (text.contains("그거") || text.contains("아니")) {
            return "follow_up";
        }
        if (text.contains("아무때나") || text.contains("이번주안에")) {
            return "availability_candidate";
        }
        if (text.contains("업무") || text.contains("빡빡") || text.contains("회의")) {
            return "workload_adjustment";
        }
        return "manual_request";
    }

    private boolean needsRoutineContext(String requestType) {
        return List.of("leave_adjustment", "travel_day", "workload_adjustment", "availability_candidate").contains(requestType);
    }

    private boolean needsWorkloadContext(String requestType) {
        return List.of("leave_adjustment", "travel_day", "workload_adjustment").contains(requestType);
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
