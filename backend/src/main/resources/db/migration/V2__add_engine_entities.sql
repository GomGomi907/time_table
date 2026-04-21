create table if not exists focus_sessions (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    scheduled_block_id uuid not null references schedule_blocks (id) on delete cascade,
    status varchar(50) not null, -- ACTIVE, PAUSED, COMPLETED, ABANDONED
    started_at timestamp with time zone,
    paused_at timestamp with time zone,
    remaining_minutes integer,
    is_paused boolean not null default false
);

create index if not exists idx_focus_sessions_user_status on focus_sessions (user_id, status);

create table if not exists interventions (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    trigger_type varchar(50) not null, -- OVERTIME, GAP, CONFLICT, GOAL_RISK
    summary text not null,
    recommendation text,
    impact_analysis text,
    affected_block_ids text, -- JSON or comma-separated UUIDs
    status varchar(50) not null, -- PENDING, ACCEPTED, REJECTED, EXPIRED
    expires_at timestamp with time zone
);

create index if not exists idx_interventions_user_status on interventions (user_id, status);
