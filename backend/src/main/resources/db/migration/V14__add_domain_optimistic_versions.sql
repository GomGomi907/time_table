alter table events add column if not exists version bigint not null default 0;
alter table tasks add column if not exists version bigint not null default 0;
alter table schedule_blocks add column if not exists version bigint not null default 0;
