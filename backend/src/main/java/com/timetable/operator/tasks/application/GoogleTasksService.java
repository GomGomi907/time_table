package com.timetable.operator.tasks.application;

import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.security.CurrentUserProvider;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleTasksService {

    private final WebClient webClient;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CurrentUserProvider currentUserProvider;

    public List<TaskItem> getMyTasks() {
        var user = currentUserProvider.getCurrentUser();
        var connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElse(null);

        if (connection == null || connection.getAccessToken() == null) {
            log.warn("No Google connection found for user: {}", user.getId());
            return Collections.emptyList();
        }

        try {
            TaskResponse response = webClient.get()
                    .uri("https://tasks.googleapis.com/tasks/v1/lists/@default/tasks?showCompleted=false")
                    .headers(h -> h.setBearerAuth(connection.getAccessToken()))
                    .retrieve()
                    .bodyToMono(TaskResponse.class)
                    .block();

            return response != null && response.items() != null ? response.items() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch Google Tasks for user: {}", user.getId(), e);
            return Collections.emptyList();
        }
    }

    public record TaskResponse(List<TaskItem> items) {}

    public record TaskItem(
            String id,
            String title,
            String notes,
            String status,
            String due,
            String updated
    ) {}
}
