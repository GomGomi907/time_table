create table if not exists google_calendar_notification_channels (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null references users (id) on delete cascade,
    calendar_connection_id uuid references calendar_connections (id) on delete set null,
    calendar_id varchar(255) not null default 'primary',
    channel_id varchar(64) not null,
    resource_id varchar(255) not null,
    resource_uri text,
    channel_token_hash text not null,
    status varchar(50) not null,
    expiration_at timestamp with time zone,
    last_message_number bigint,
    last_notification_at timestamp with time zone,
    replaced_by_channel_id varchar(64)
);

create unique index if not exists idx_google_calendar_notification_channels_channel_id
    on google_calendar_notification_channels (channel_id);

create index if not exists idx_google_calendar_notification_channels_lookup
    on google_calendar_notification_channels (channel_id, resource_id, status);

create index if not exists idx_google_calendar_notification_channels_active_renewal
    on google_calendar_notification_channels (user_id, calendar_id, status, expiration_at);

alter table if exists sync_logs add column if not exists webhook_resource_id varchar(255);
alter table if exists sync_logs add column if not exists webhook_message_number bigint;
alter table if exists sync_logs add column if not exists webhook_resource_uri text;
