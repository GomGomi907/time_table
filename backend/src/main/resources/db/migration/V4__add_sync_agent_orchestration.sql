alter table users add column if not exists timezone varchar(255) not null default 'Asia/Seoul';
alter table users add column if not exists auto_reschedule_enabled boolean not null default false;
alter table users add column if not exists focus_auto_enter_enabled boolean not null default false;
alter table users add column if not exists profile_image_url varchar(1000);

alter table goals add column if not exists goal_type varchar(50) not null default 'DURATION';
alter table goals add column if not exists metric_unit varchar(255);
alter table goals add column if not exists target_value numeric(12, 2);
alter table goals add column if not exists current_value numeric(12, 2);
alter table goals add column if not exists progress_rule text;
alter table goals add column if not exists start_date date;
alter table goals add column if not exists end_date date;
alter table goals add column if not exists priority smallint not null default 3;

alter table if exists sync_logs add column if not exists provider varchar(50) not null default 'google';
alter table if exists sync_logs add column if not exists trigger_source varchar(50) not null default 'MANUAL';
alter table if exists sync_logs add column if not exists resolve_policy varchar(50) not null default 'PROPOSAL_FIRST';
alter table if exists sync_logs add column if not exists range_start timestamp with time zone;
alter table if exists sync_logs add column if not exists range_end timestamp with time zone;
alter table if exists sync_logs add column if not exists webhook_channel_id varchar(255);
alter table if exists sync_logs add column if not exists webhook_resource_state varchar(255);
alter table if exists sync_logs add column if not exists started_at timestamp with time zone;
alter table if exists sync_logs add column if not exists finished_at timestamp with time zone;

alter table if exists reschedule_suggestions add column if not exists reason text;
alter table if exists reschedule_suggestions add column if not exists explanation text;
alter table if exists reschedule_suggestions add column if not exists execution_snapshot text;
alter table if exists reschedule_suggestions add column if not exists rejected_at timestamp with time zone;
alter table if exists reschedule_suggestions add column if not exists expires_at timestamp with time zone;

alter table if exists chat_command_logs add column if not exists normalized_message text;
alter table if exists chat_command_logs add column if not exists explanation text;

create table if not exists sync_logs (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    sync_type varchar(100) not null,
    provider varchar(50) not null,
    target_system varchar(50) not null,
    direction varchar(50) not null,
    trigger_source varchar(50) not null,
    resolve_policy varchar(50) not null,
    status varchar(50) not null,
    detail text,
    affected_count integer not null default 0,
    range_start timestamp with time zone,
    range_end timestamp with time zone,
    webhook_channel_id varchar(255),
    webhook_resource_state varchar(255),
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone
);

create index if not exists idx_sync_logs_user_target_created
    on sync_logs (user_id, target_system, created_at);

create table if not exists sync_conflicts (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    sync_log_id uuid references sync_logs (id) on delete set null,
    provider varchar(50) not null,
    target_system varchar(50) not null,
    summary text not null,
    details text,
    local_ref varchar(255),
    external_ref varchar(255),
    status varchar(50) not null,
    resolution varchar(50),
    payload text,
    resolved_at timestamp with time zone
);

create index if not exists idx_sync_conflicts_user_status
    on sync_conflicts (user_id, status, created_at);

create table if not exists reschedule_suggestions (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    trigger_type varchar(50) not null,
    status varchar(50) not null,
    summary text not null,
    reason text,
    explanation text,
    suggestion_payload text not null,
    execution_snapshot text,
    applied_at timestamp with time zone,
    rejected_at timestamp with time zone,
    reverted_at timestamp with time zone,
    expires_at timestamp with time zone
);

create index if not exists idx_reschedule_suggestions_user_status
    on reschedule_suggestions (user_id, status, created_at);

create table if not exists chat_command_logs (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    raw_message text not null,
    normalized_message text,
    parsed_intent varchar(100) not null,
    parsed_payload text,
    execution_type varchar(50) not null,
    result_status varchar(50) not null,
    explanation text,
    result_payload text
);

create index if not exists idx_chat_command_logs_user_created
    on chat_command_logs (user_id, created_at);

create table if not exists priority_adjustment_proposals (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    target_type varchar(50) not null,
    target_id uuid not null,
    current_priority smallint not null,
    proposed_priority smallint not null,
    reason text,
    status varchar(50) not null,
    decided_at timestamp with time zone
);

create index if not exists idx_priority_proposals_user_status
    on priority_adjustment_proposals (user_id, status, created_at);
