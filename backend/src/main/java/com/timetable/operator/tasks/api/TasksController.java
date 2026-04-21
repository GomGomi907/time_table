package com.timetable.operator.tasks.api;

import com.timetable.operator.tasks.application.GoogleTasksService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TasksController {

    private final GoogleTasksService googleTasksService;

    @GetMapping
    public List<GoogleTasksService.TaskItem> getTasks() {
        return googleTasksService.getMyTasks();
    }
}
