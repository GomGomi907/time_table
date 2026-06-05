package com.timetable.operator.agent.application.context;

import java.util.List;

public record ContextPackage(
        String requestType,
        TemporalScope temporalScope,
        List<ContextSection> includedSections,
        List<ContextSection> excludedSections,
        int characterEstimate,
        int privacyExposureScore
) {
    public record TemporalScope(String start, String end, String timezone) {
    }

    public record ContextSection(String name, String reason, int itemCount, boolean redacted) {
    }
}
