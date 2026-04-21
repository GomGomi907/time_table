package com.timetable.operator.engine.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.engine.domain.FocusSession;
import com.timetable.operator.engine.domain.FocusSessionStatus;
import com.timetable.operator.engine.infrastructure.FocusSessionRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FocusSessionService {

    private final FocusSessionRepository focusSessionRepository;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public Optional<FocusSession> getActiveSession() {
        AppUser user = currentUserProvider.getCurrentUser();
        return focusSessionRepository.findByUserIdAndStatus(user.getId(), FocusSessionStatus.ACTIVE);
    }

    @Transactional
    public FocusSession startSession(UUID scheduledBlockId) {
        AppUser user = currentUserProvider.getCurrentUser();
        
        // 기존 활성 세션이 있으면 종료 (또는 예외 처리)
        focusSessionRepository.findByUserIdAndStatus(user.getId(), FocusSessionStatus.ACTIVE)
            .ifPresent(session -> {
                session.setStatus(FocusSessionStatus.ABANDONED);
                focusSessionRepository.save(session);
            });

        FocusSession session = new FocusSession();
        session.setUserId(user.getId());
        session.setScheduledBlockId(scheduledBlockId);
        session.setStatus(FocusSessionStatus.ACTIVE);
        session.setStartedAt(OffsetDateTime.now());
        session.setPaused(false);
        
        return focusSessionRepository.save(session);
    }

    @Transactional
    public void completeSession(UUID sessionId) {
        FocusSession session = focusSessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        session.setStatus(FocusSessionStatus.COMPLETED);
        focusSessionRepository.save(session);
    }

    @Transactional
    public void pauseSession(UUID sessionId) {
        FocusSession session = focusSessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        if (session.getStatus() == FocusSessionStatus.ACTIVE && !session.isPaused()) {
            session.setPaused(true);
            session.setPausedAt(OffsetDateTime.now());
            focusSessionRepository.save(session);
        }
    }

    @Transactional
    public void resumeSession(UUID sessionId) {
        FocusSession session = focusSessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        if (session.isPaused()) {
            session.setPaused(false);
            session.setPausedAt(null);
            focusSessionRepository.save(session);
        }
    }
}
