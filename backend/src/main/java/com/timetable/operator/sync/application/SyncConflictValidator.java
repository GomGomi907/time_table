package com.timetable.operator.sync.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.api.UserActionRequiredException;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.sync.domain.SyncConflict;
import com.timetable.operator.sync.domain.SyncConflictStatus;
import com.timetable.operator.sync.infrastructure.SyncConflictRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncConflictValidator {
    private static final String DEFAULT_PENDING_CONFLICT_MESSAGE =
            "확인이 필요한 일정 충돌이 있습니다. 충돌 해결 화면에서 먼저 선택해 주세요.";

    private final CurrentUserProvider currentUserProvider;
    private final SyncConflictRepository syncConflictRepository;

    @Transactional(readOnly = true)
    public PendingConflictStatus getCurrentUserPendingConflictStatus() {
        AppUser user = currentUserProvider.getCurrentUser();
        long pendingCount = syncConflictRepository.countByUserIdAndStatus(user.getId(), SyncConflictStatus.PENDING);
        if (pendingCount == 0) {
            return PendingConflictStatus.clear();
        }

        Optional<SyncConflict> newestPendingConflict =
                syncConflictRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                        user.getId(),
                        SyncConflictStatus.PENDING
                );
        if (newestPendingConflict.isEmpty()) {
            return PendingConflictStatus.pending(DEFAULT_PENDING_CONFLICT_MESSAGE, pendingCount);
        }

        SyncConflict newestConflict = newestPendingConflict.get();
        String summary = newestConflict.getSummary();
        String message = summary == null || summary.isBlank()
                ? DEFAULT_PENDING_CONFLICT_MESSAGE
                : "%s %s".formatted(DEFAULT_PENDING_CONFLICT_MESSAGE, summary.trim());
        return PendingConflictStatus.pending(message, pendingCount);
    }

    @Transactional(readOnly = true)
    public void assertCurrentUserHasNoPendingConflicts() {
        PendingConflictStatus status = getCurrentUserPendingConflictStatus();
        if (status.pending()) {
            throw new UserActionRequiredException(status.message());
        }
    }

    public record PendingConflictStatus(
            boolean pending,
            String message,
            long count
    ) {
        public static PendingConflictStatus clear() {
            return new PendingConflictStatus(false, null, 0);
        }

        public static PendingConflictStatus pending(String message, long count) {
            return new PendingConflictStatus(true, message, count);
        }
    }
}
