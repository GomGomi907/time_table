# Google Inbound Sync Implementation Phase

Date: 2026-05-02

## Scope

Implement real inbound reads from Google Calendar and Google Tasks. External writes remain out of scope until conflict handling and user-confirmed write UX are proven.

## Success Criteria

- Manual Calendar sync reads provider events into `events`.
- Manual Tasks sync reads provider tasks into `tasks`.
- Sync is idempotent by provider object id and etag.
- Detached/deleted provider items do not hard-delete local user edits.
- Sync run status reports affected counts and provider errors honestly.
- Dashboard sync copy changes from scaffold mode only after real reads are enabled.

## Tasks

- [x] Add a Google access-token adapter with expired-token detection and explicit degraded connection status.
- [x] Add Calendar inbound client for `events.list` with range filtering and pagination.
- [x] Map Google Calendar event fields into local `events` without overwriting local-only fields.
- [x] Add Tasks inbound client for task lists and tasks with pagination.
- [x] Map Google Tasks fields into local `tasks` without overwriting local-only fields.
- [x] Add idempotent upsert rules using `external_source_id`, `external_etag`, and `last_synced_at`.
- [x] Record provider failures in `sync_log_entries` and `calendar_connections.last_sync_error`.
- [x] Add integration-style service tests with mocked provider responses.
- [x] Flip `externalReadEnabled` only after both Calendar and Tasks inbound flows pass tests.

## Deferred

- Provider write-back.
- Webhook channel creation and renewal.
- Multi-account provider connection.
- User-facing conflict resolution UI beyond current sync conflict records.

## References

- Google Calendar API `events.list`: https://developers.google.com/calendar/api/v3/reference/events/list
- Google Tasks API `tasklists.list`: https://developers.google.com/workspace/tasks/reference/rest/v1/tasklists/list
- Google Tasks API `tasks.list`: https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/list
