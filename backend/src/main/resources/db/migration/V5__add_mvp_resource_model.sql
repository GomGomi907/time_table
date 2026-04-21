create index if not exists idx_goals_user_status_end_date on goals (user_id, status, end_date);

update goals
set current_value = 0
where current_value is null;

update goals
set progress_rule = '{"type":"manual"}'
where progress_rule is null;

create table if not exists events (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    goal_id uuid references goals (id) on delete set null,
    title varchar(255) not null,
    description text,
    start_at timestamp with time zone not null,
    end_at timestamp with time zone not null,
    actual_start_at timestamp with time zone,
    actual_end_at timestamp with time zone,
    priority smallint not null default 3,
    status varchar(50) not null,
    category varchar(50) not null,
    source_type varchar(50) not null,
    sync_state varchar(50) not null,
    forked_from_event_id uuid,
    external_source_id varchar(255),
    external_etag varchar(255),
    last_synced_at timestamp with time zone,
    constraint chk_events_priority_range check (priority between 1 and 5),
    constraint chk_events_time_range check (end_at > start_at),
    constraint chk_events_actual_time_range check (
        actual_end_at is null
        or actual_start_at is null
        or actual_end_at >= actual_start_at
    )
);

create index if not exists idx_events_user_start_at on events (user_id, start_at);
create index if not exists idx_events_user_status_start_at on events (user_id, status, start_at);
create index if not exists idx_events_external_source_id on events (external_source_id);
create index if not exists idx_events_goal_id on events (goal_id);

create table if not exists tasks (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    goal_id uuid references goals (id) on delete set null,
    event_id uuid references events (id) on delete set null,
    title varchar(255) not null,
    description text,
    category varchar(50),
    due_date timestamp with time zone,
    estimated_minutes integer not null default 0,
    actual_minutes integer not null default 0,
    completed_at timestamp with time zone,
    priority smallint not null default 3,
    status varchar(50) not null,
    source_type varchar(50) not null,
    sync_state varchar(50) not null,
    forked_from_task_id uuid,
    external_source_id varchar(255),
    external_etag varchar(255),
    last_synced_at timestamp with time zone,
    constraint chk_tasks_priority_range check (priority between 1 and 5),
    constraint chk_tasks_estimated_minutes check (estimated_minutes >= 0),
    constraint chk_tasks_actual_minutes check (actual_minutes >= 0)
);

create index if not exists idx_tasks_user_due_date on tasks (user_id, due_date);
create index if not exists idx_tasks_user_status_priority on tasks (user_id, status, priority);
create index if not exists idx_tasks_goal_id on tasks (goal_id);
create index if not exists idx_tasks_event_id on tasks (event_id);
create index if not exists idx_tasks_external_source_id on tasks (external_source_id);

create table if not exists focus_session_logs (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    event_id uuid references events (id) on delete set null,
    task_id uuid references tasks (id) on delete set null,
    started_at timestamp with time zone not null,
    ended_at timestamp with time zone,
    completion_type varchar(50),
    trigger_source varchar(50) not null,
    memo text,
    constraint chk_focus_logs_time_range check (
        ended_at is null
        or ended_at >= started_at
    )
);

create index if not exists idx_focus_logs_user_started_at on focus_session_logs (user_id, started_at);
create index if not exists idx_focus_logs_event_id on focus_session_logs (event_id);
create index if not exists idx_focus_logs_task_id on focus_session_logs (task_id);

create table if not exists sync_mappings (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    local_type varchar(50) not null,
    local_id uuid not null,
    provider varchar(50) not null,
    external_id varchar(255) not null,
    external_etag varchar(255),
    sync_status varchar(50) not null,
    tombstone_state varchar(50) not null,
    remote_deleted_at timestamp with time zone,
    local_deleted_at timestamp with time zone,
    last_synced_at timestamp with time zone,
    metadata text
);

create unique index if not exists idx_sync_mappings_provider_external_id on sync_mappings (provider, external_id);
create unique index if not exists idx_sync_mappings_local on sync_mappings (local_type, local_id, provider);
create index if not exists idx_sync_mappings_local_lookup on sync_mappings (local_type, local_id);
