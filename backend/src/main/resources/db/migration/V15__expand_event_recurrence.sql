alter table events add column if not exists rrule text;
alter table events add column if not exists recurrence_json text;
alter table events add column if not exists recurring_event_id uuid;
alter table events add column if not exists provider_recurring_event_id varchar(255);
alter table events add column if not exists original_start_time timestamp with time zone;
alter table events add column if not exists recurrence_instance_type varchar(32) not null default 'SINGLE';

alter table events
    add constraint chk_events_recurrence_instance_type
    check (recurrence_instance_type in ('SINGLE', 'MASTER', 'INSTANCE_OVERRIDE', 'CANCELLED_INSTANCE'));

alter table events
    add constraint chk_events_recurrence_master_requires_rrule
    check (recurrence_instance_type <> 'MASTER' or rrule is not null);

alter table events
    add constraint chk_events_recurrence_exception_requires_parent_and_original_start
    check (
        recurrence_instance_type not in ('INSTANCE_OVERRIDE', 'CANCELLED_INSTANCE')
        or (
            original_start_time is not null
            and (recurring_event_id is not null or provider_recurring_event_id is not null)
        )
    );

create index if not exists idx_events_user_start_end on events (user_id, start_at, end_at);
create index if not exists idx_events_user_recurrence_type on events (user_id, recurrence_instance_type);
create index if not exists idx_events_user_recurring_original on events (user_id, recurring_event_id, original_start_time);
create index if not exists idx_events_user_provider_recurring_original on events (user_id, provider_recurring_event_id, original_start_time);
create index if not exists idx_events_user_external_source on events (user_id, external_source_id);

alter table schedule_blocks add column if not exists shadow_policy varchar(32) not null default 'AUTO_SHADOW';
alter table schedule_blocks add column if not exists protected_window boolean not null default false;

alter table schedule_blocks
    add constraint chk_schedule_blocks_shadow_policy
    check (shadow_policy in ('AUTO_SHADOW', 'NEVER_SHADOW', 'SANCTUARY_PROTECTED'));

create table if not exists routine_shadow_overrides (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    schedule_block_id uuid not null references schedule_blocks (id) on delete cascade,
    shadow_date date not null,
    shadow_state varchar(50) not null,
    shadowing_entity_type varchar(50),
    shadowing_entity_id uuid,
    reason varchar(500) not null,
    resolved_at timestamp with time zone,
    constraint chk_routine_shadow_state check (
        shadow_state in ('NONE', 'SHADOWED_BY_EVENT', 'SHADOWED_BY_TASK', 'SANCTUARY_BLOCKED', 'USER_OVERRIDDEN')
    ),
    constraint chk_routine_shadow_entity_type check (
        shadowing_entity_type is null or shadowing_entity_type in ('EVENT', 'TASK', 'ROUTINE_BLOCK')
    )
);

create unique index if not exists idx_routine_shadow_user_block_date
    on routine_shadow_overrides (user_id, schedule_block_id, shadow_date);
create index if not exists idx_routine_shadow_user_date
    on routine_shadow_overrides (user_id, shadow_date);
create index if not exists idx_routine_shadow_entity
    on routine_shadow_overrides (shadowing_entity_type, shadowing_entity_id);
