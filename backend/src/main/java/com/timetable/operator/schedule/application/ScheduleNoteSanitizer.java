package com.timetable.operator.schedule.application;

import java.util.regex.Pattern;

public final class ScheduleNoteSanitizer {

    private static final Pattern GENERATED_OR_INTERNAL_NOTE = Pattern.compile(
            "(?i)(AI|인공지능|제안합니다|제안해요|권장합니다|추천합니다|추천해요|추천|근거|예상 영향"
                    + "|확인한 내용|기준으로 재배치|조정안|최적화|리스크|검증|판단|추론|전략|의도"
                    + "|사용자(?:의)?\\s*패턴|사용자(?:의)?\\s*선호|회복 시간을 지키도록"
                    + "|주말.*(?:비워|회복|제안)|비워두고.*회복|완충|버퍼|컨디션 리셋"
                    + "|e2e|playwright|qa seed|service improvement|dashboard briefing pending approval)"
    );

    private ScheduleNoteSanitizer() {
    }

    public static String cleanManualNote(String value) {
        return blankToNull(value);
    }

    public static String cleanGeneratedNote(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || GENERATED_OR_INTERNAL_NOTE.matcher(normalized).find()) {
            return null;
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
