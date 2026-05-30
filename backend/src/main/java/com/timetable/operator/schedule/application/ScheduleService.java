package com.timetable.operator.schedule.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScheduleService {
    private static final List<DayOfWeek> DAY_ORDER = List.of(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
    );

    private final ScheduleBlockRepository scheduleBlockRepository;
    private final CurrentUserProvider currentUserProvider;
    private final GeminiScheduleClient geminiScheduleClient;
    private final ScheduleBlockRules scheduleBlockRules;

    @Transactional
    public WeekScheduleResponse getWeeklySchedule() {
        AppUser user = currentUserProvider.getCurrentUser();
        return WeekScheduleResponse.fromBlocks(scheduleBlockRepository.findByUserId(user.getId()));
    }

    @Transactional
    public WeekScheduleResponse importSchedule(ImportScheduleRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        List<GeminiScheduleClient.ImportedScheduleBlock> importedBlocks =
                geminiScheduleClient.normalize(request.rawText().trim());

        List<ScheduleBlock> persistedBlocks = importedBlocks.stream()
                .map(block -> toEntity(user, block))
                .toList();
        scheduleBlockRules.validateBatch(user.getId(), persistedBlocks, request.replaceExisting());

        if (request.replaceExisting()) {
            scheduleBlockRepository.deleteByUserId(user.getId());
        }
        scheduleBlockRepository.saveAll(persistedBlocks);

        return WeekScheduleResponse.fromBlocks(scheduleBlockRepository.findByUserId(user.getId()));
    }

    @Transactional
    public TimeBlockResponse createBlock(ScheduleBlockWriteRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        ScheduleBlock block = new ScheduleBlock();
        block.setUserId(user.getId());
        applyManualBlock(block, request);
        scheduleBlockRules.validateForUser(user.getId(), block);
        return TimeBlockResponse.from(scheduleBlockRepository.save(block));
    }

    @Transactional
    public TimeBlockResponse updateBlock(UUID blockId, ScheduleBlockWriteRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(blockId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 일정 블록을 찾을 수 없습니다."));
        applyManualBlock(block, request);
        scheduleBlockRules.validateForUser(user.getId(), block);
        return TimeBlockResponse.from(scheduleBlockRepository.save(block));
    }

    @Transactional
    public void deleteBlock(UUID blockId) {
        AppUser user = currentUserProvider.getCurrentUser();
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(blockId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 일정 블록을 찾을 수 없습니다."));
        scheduleBlockRepository.delete(block);
    }

    private ScheduleBlock toEntity(AppUser user, GeminiScheduleClient.ImportedScheduleBlock block) {
        ScheduleBlock scheduleBlock = new ScheduleBlock();
        scheduleBlock.setUserId(user.getId());
        scheduleBlock.setDayOfWeek(block.dayOfWeek());
        scheduleBlock.setStartTime(block.startTime());
        scheduleBlock.setEndTime(block.endTime());
        scheduleBlock.setActivity(block.activity());
        scheduleBlock.setCategory(parseCategory(block.category()));
        scheduleBlock.setNote(blankToNull(block.note()));
        scheduleBlock.setSourceType(ScheduleSourceType.AI_IMPORT);
        scheduleBlock.setSourceRef("gemini-api");
        return scheduleBlock;
    }

    private ScheduleCategory parseCategory(String category) {
        return ScheduleCategory.valueOf(category.trim().toUpperCase(Locale.ROOT));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void applyManualBlock(ScheduleBlock block, ScheduleBlockWriteRequest request) {
        block.setDayOfWeek(request.dayOfWeek());
        block.setStartTime(request.startTime());
        block.setEndTime(request.endTime());
        block.setActivity(request.activity().trim());
        block.setCategory(request.category());
        block.setNote(blankToNull(request.note()));
        block.setSourceType(ScheduleSourceType.MANUAL);
        block.setSourceRef("manual-edit");
    }

    public record ImportScheduleRequest(
            @NotBlank String rawText,
            boolean replaceExisting
    ) {
    }

    public record ScheduleBlockWriteRequest(
            @NotNull DayOfWeek dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotBlank @Size(max = 255) String activity,
            @NotNull ScheduleCategory category,
            @Size(max = 1000) String note
    ) {
    }

    public record WeekScheduleResponse(
            List<DailyRoutineResponse> week
    ) {
        static WeekScheduleResponse fromBlocks(List<ScheduleBlock> blocks) {
            EnumMap<DayOfWeek, List<ScheduleBlock>> grouped = new EnumMap<>(DayOfWeek.class);
            for (DayOfWeek day : DAY_ORDER) {
                grouped.put(day, new ArrayList<>());
            }
            for (ScheduleBlock block : blocks) {
                grouped.computeIfAbsent(block.getDayOfWeek(), ignored -> new ArrayList<>()).add(block);
            }

            List<DailyRoutineResponse> week = DAY_ORDER.stream()
                    .map(day -> new DailyRoutineResponse(
                            day.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                            grouped.getOrDefault(day, List.of()).stream()
                                    .sorted(Comparator
                                            .comparingInt((ScheduleBlock block) -> displayOrder(block.getStartTime()))
                                            .thenComparing(ScheduleBlock::getEndTime))
                                    .map(TimeBlockResponse::from)
                                    .toList()
                    ))
                    .toList();
            return new WeekScheduleResponse(week);
        }

        private static int displayOrder(LocalTime time) {
            int seconds = time.toSecondOfDay();
            if (time.isBefore(ScheduleBlockRules.OVERNIGHT_DISPLAY_CUTOFF)) {
                return seconds + (24 * 60 * 60);
            }
            return seconds;
        }
    }

    public record DailyRoutineResponse(
            String dayOfWeek,
            List<TimeBlockResponse> blocks
    ) {
    }

    public record TimeBlockResponse(
            String id,
            String startTime,
            String endTime,
            String activity,
            String category,
            String note,
            String sourceType,
            String sourceRef
    ) {
        public static TimeBlockResponse from(ScheduleBlock block) {
            return new TimeBlockResponse(
                    block.getId().toString(),
                    block.getStartTime().toString(),
                    block.getEndTime().toString(),
                    block.getActivity(),
                    block.getCategory().name(),
                    block.getNote(),
                    block.getSourceType().name(),
                    block.getSourceRef()
            );
        }
    }
}
