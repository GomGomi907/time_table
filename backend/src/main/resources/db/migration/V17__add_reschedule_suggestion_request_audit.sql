alter table reschedule_suggestions
    add column if not exists original_request text;

alter table reschedule_suggestions
    add column if not exists decision_reason text;

update reschedule_suggestions
set original_request = reason
where original_request is null
  and reason is not null;
