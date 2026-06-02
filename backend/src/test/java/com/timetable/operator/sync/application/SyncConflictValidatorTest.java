package com.timetable.operator.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.api.UserActionRequiredException;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.sync.domain.SyncConflict;
import com.timetable.operator.sync.domain.SyncConflictStatus;
import com.timetable.operator.sync.infrastructure.SyncConflictRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SyncConflictValidatorTest {

    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final SyncConflictRepository syncConflictRepository = mock(SyncConflictRepository.class);
    private final SyncConflictValidator validator = new SyncConflictValidator(
            currentUserProvider,
            syncConflictRepository
    );

    @Test
    void pendingConflictHardBlocksScheduleMutations() {
        UUID userId = UUID.randomUUID();
        AppUser user = user(userId);
        SyncConflict conflict = new SyncConflict();
        conflict.setSummary("Google 일정과 로컬 일정 시간이 다릅니다.");

        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(syncConflictRepository.countByUserIdAndStatus(userId, SyncConflictStatus.PENDING)).thenReturn(1L);
        when(syncConflictRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, SyncConflictStatus.PENDING))
                .thenReturn(Optional.of(conflict));

        SyncConflictValidator.PendingConflictStatus status =
                validator.getCurrentUserPendingConflictStatus();

        assertThat(status.pending()).isTrue();
        assertThat(status.count()).isEqualTo(1);
        assertThat(status.message()).contains("충돌").contains("Google 일정");
        assertThatThrownBy(validator::assertCurrentUserHasNoPendingConflicts)
                .isInstanceOf(UserActionRequiredException.class)
                .hasMessageContaining("충돌");
    }

    @Test
    void noPendingConflictAllowsScheduleMutations() {
        UUID userId = UUID.randomUUID();
        AppUser user = user(userId);

        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(syncConflictRepository.countByUserIdAndStatus(userId, SyncConflictStatus.PENDING)).thenReturn(0L);

        SyncConflictValidator.PendingConflictStatus status =
                validator.getCurrentUserPendingConflictStatus();

        assertThat(status.pending()).isFalse();
        assertThat(status.count()).isZero();
        assertThat(status.message()).isNull();
        validator.assertCurrentUserHasNoPendingConflicts();
    }

    private static AppUser user(UUID userId) {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("test@example.com");
        user.setDisplayName("Test");
        user.setProvider("local");
        return user;
    }
}
