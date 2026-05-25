alter table calendar_connections add column if not exists granted_scopes text;
alter table calendar_connections add column if not exists calendar_read_enabled boolean not null default false;
alter table calendar_connections add column if not exists calendar_write_enabled boolean not null default false;
alter table calendar_connections add column if not exists tasks_read_enabled boolean not null default false;
alter table calendar_connections add column if not exists tasks_write_enabled boolean not null default false;
alter table calendar_connections add column if not exists capability_checked_at timestamp with time zone;
alter table calendar_connections add column if not exists capability_status varchar(100);
alter table calendar_connections add column if not exists capability_error text;

create table if not exists provider_write_outbox (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    local_type varchar(50) not null,
    local_id uuid not null,
    mapping_id uuid references sync_mappings (id) on delete set null,
    provider varchar(50) not null,
    operation varchar(50) not null,
    payload_snapshot text,
    state varchar(50) not null,
    attempt_count integer not null default 0,
    last_error_code varchar(100),
    last_error_message text,
    next_retry_at timestamp with time zone,
    in_flight_at timestamp with time zone,
    applied_at timestamp with time zone
);

create index if not exists idx_provider_write_outbox_user_state
    on provider_write_outbox (user_id, state, created_at);

create index if not exists idx_provider_write_outbox_local
    on provider_write_outbox (local_type, local_id, provider);
