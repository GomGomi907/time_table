alter table onboarding_profiles add column if not exists focus_session_minutes integer not null default 45;
alter table onboarding_profiles add column if not exists focus_break_minutes integer not null default 10;
alter table onboarding_profiles add column if not exists focus_intervention_style varchar(50) not null default 'balanced';

alter table user_preferences add column if not exists preferred_focus_minutes integer not null default 45;
alter table user_preferences add column if not exists break_buffer_minutes integer not null default 10;
