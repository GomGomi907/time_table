package com.timetable.operator.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SyncTargetSystemTest {

    @Test
    void acceptsTaskTargetAliasesIncludingCamelCase() {
        assertThat(SyncTargetSystem.from("googleTasks")).isEqualTo(SyncTargetSystem.GOOGLE_TASKS);
        assertThat(SyncTargetSystem.from("google_tasks")).isEqualTo(SyncTargetSystem.GOOGLE_TASKS);
        assertThat(SyncTargetSystem.from("google-tasks")).isEqualTo(SyncTargetSystem.GOOGLE_TASKS);
        assertThat(SyncTargetSystem.from("tasks")).isEqualTo(SyncTargetSystem.GOOGLE_TASKS);
    }

    @Test
    void acceptsCalendarAliasesAndKeepsLegacyDefault() {
        assertThat(SyncTargetSystem.from("googleCalendar")).isEqualTo(SyncTargetSystem.GOOGLE_CALENDAR);
        assertThat(SyncTargetSystem.from("google_calendar")).isEqualTo(SyncTargetSystem.GOOGLE_CALENDAR);
        assertThat(SyncTargetSystem.from("calendar")).isEqualTo(SyncTargetSystem.GOOGLE_CALENDAR);
        assertThat(SyncTargetSystem.from(null)).isEqualTo(SyncTargetSystem.GOOGLE_CALENDAR);
        assertThat(SyncTargetSystem.from("unknown")).isEqualTo(SyncTargetSystem.GOOGLE_CALENDAR);
    }
}
