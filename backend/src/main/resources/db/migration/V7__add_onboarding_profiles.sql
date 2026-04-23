create table if not exists onboarding_profiles (
    id uuid primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    user_id uuid not null unique references users (id) on delete cascade,
    wake_time time,
    work_start_time time,
    dinner_time time,
    sleep_time time,
    weekend_style varchar(50),
    bootstrap_completed_at timestamp with time zone,
    onboarding_completed_at timestamp with time zone
);

create index if not exists idx_onboarding_profiles_user_id on onboarding_profiles (user_id);
