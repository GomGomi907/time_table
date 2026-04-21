create table if not exists users (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    google_subject varchar(255),
    email varchar(255) not null,
    display_name varchar(255) not null,
    provider varchar(50) not null,
    demo_user boolean not null default false
);

create unique index if not exists idx_users_google_subject on users (google_subject);
create unique index if not exists idx_users_email on users (email);

create table if not exists calendar_connections (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    provider varchar(50) not null,
    status varchar(50) not null,
    google_subject varchar(255),
    email varchar(255),
    access_token text,
    refresh_token text,
    token_expires_at timestamp with time zone,
    last_successful_sync_at timestamp with time zone,
    last_sync_error text
);

create unique index if not exists idx_calendar_connections_user_provider on calendar_connections (user_id, provider);

create table if not exists calendar_sync_runs (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    status varchar(50) not null,
    synced_from timestamp with time zone not null,
    synced_to timestamp with time zone not null,
    imported_count integer not null default 0,
    error_message text,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone
);

create table if not exists user_preferences (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null unique references users (id) on delete cascade,
    quiet_hours_start time not null,
    quiet_hours_end time not null,
    buffer_minutes integer not null,
    overtime_trigger_minutes integer not null,
    open_gap_trigger_minutes integer not null,
    intervention_frequency varchar(50) not null
);

create table if not exists goals (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    parent_goal_id uuid references goals (id) on delete cascade,
    title varchar(255) not null,
    description varchar(1000),
    category varchar(50) not null,
    status varchar(50) not null,
    progress integer not null default 0
);

create index if not exists idx_goals_user_id on goals (user_id);
create index if not exists idx_goals_parent_goal_id on goals (parent_goal_id);

create table if not exists schedule_blocks (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    day_of_week varchar(20) not null,
    start_time time not null,
    end_time time not null,
    activity varchar(255) not null,
    category varchar(50) not null,
    note varchar(1000),
    source_type varchar(50) not null,
    source_ref varchar(255)
);

create index if not exists idx_schedule_blocks_user_day on schedule_blocks (user_id, day_of_week);
