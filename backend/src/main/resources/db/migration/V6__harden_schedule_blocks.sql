alter table schedule_blocks
    add constraint chk_schedule_blocks_non_zero_duration
    check (start_time <> end_time);
