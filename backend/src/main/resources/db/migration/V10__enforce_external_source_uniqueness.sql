-- Existing local/test databases may contain duplicate imported provider IDs from repeated mock sync runs.
-- Keep every row, but move the later duplicates to deterministic local-only duplicate IDs before enforcing uniqueness.
update tasks
set external_source_id = concat('duplicate:', cast(id as varchar), ':', substring(external_source_id, 1, 180))
where id in (
    select id
    from (
        select
            id,
            row_number() over (
                partition by user_id, external_source_id
                order by created_at asc, id asc
            ) as duplicate_rank
        from tasks
        where external_source_id is not null
    ) ranked_tasks
    where duplicate_rank > 1
);

update events
set external_source_id = concat('duplicate:', cast(id as varchar), ':', substring(external_source_id, 1, 180))
where id in (
    select id
    from (
        select
            id,
            row_number() over (
                partition by user_id, external_source_id
                order by created_at asc, id asc
            ) as duplicate_rank
        from events
        where external_source_id is not null
    ) ranked_events
    where duplicate_rank > 1
);

create unique index if not exists idx_tasks_user_external_source_unique
    on tasks (user_id, external_source_id);

create unique index if not exists idx_events_user_external_source_unique
    on events (user_id, external_source_id);
