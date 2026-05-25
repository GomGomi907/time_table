# Architecture Lock — Google Write-back and Canonical Scheduling Model

Date: 2026-05-15
Source plan: `.omx/plans/ralplan-complete-time-table-project-20260515T021405Z.md`
Status: Phase 0.5 execution gate

## Decision

Before implementing Google provider write-back, `Time Table` will lock the scheduling architecture around these rules:

1. **Events and Tasks are the canonical user-data mutation model.**
2. **ScheduleBlocks remain a weekly routine/projection compatibility layer.**
3. **SyncMapping owns provider identity, etags, tombstones, and mapping status.**
4. **CalendarConnection must persist granted OAuth capabilities/scopes.**
5. **Provider writes must be durable through a write outbox/state machine.**
6. **Imported item edits keep the provider mapping by default and become pending writes; fork/detach is explicit.**

These rules preserve the user-facing approval-first policy while making Google write-back testable and recoverable.

## Context

The current codebase already has usable MVP surfaces:

- `schedule_blocks` and `ScheduleService` power the weekly routine UI.
- `events` and `tasks` power executable focus items and Google inbound sync.
- `reschedule_suggestions` stores pending AI suggestions and approval/reject/revert state.
- `sync_mappings` exists but current Google inbound sync primarily uses direct `external_source_id` / `external_etag` fields.
- Google integration is currently read-only inbound; provider writes are explicitly disabled.

The execution-ready spec requires the local flow:

Google inbound sync → local schedule/task/focus state → AI proposal → user approval → local mutation → Google write-back → consistent UI state.

Without this architecture lock, provider writes could be implemented against the wrong local source of truth and create duplicate or stale Google objects.

## Canonical mutation model

### Authoritative entities

`events` and `tasks` are the canonical mutable user-data model for execution and provider-backed data.

| Operation source | Canonical mutation target |
| --- | --- |
| Manual event/task CRUD | `events` / `tasks` |
| Focus complete/postpone/extend/delete | `events` / `tasks` |
| AI-approved schedule changes | `events` / `tasks` |
| Google Calendar inbound | `events` |
| Google Tasks inbound | `tasks` |
| Provider write-back | `events` / `tasks` + `sync_mappings` |

### ScheduleBlocks role

`schedule_blocks` remains a weekly routine/projection layer:

- It can continue powering current weekly routine CRUD during migration.
- It may be displayed alongside provider-backed events/tasks.
- It must not be the only executable path for AI-approved provider-backed changes.
- If a ScheduleBlock action should affect Google, the action must be converted into a canonical Event/Task operation first.
- UI copy should distinguish routine/local blocks from Google-backed events/tasks until the models are fully unified.

### Acceptance invariant

An approved AI move on a Google-imported calendar event must update the canonical Event/SyncMapping path, and dashboard, weekly schedule, and focus mode must all display the same resulting time.

## SyncMapping ownership

`sync_mappings` is the source of truth for provider identity and sync lifecycle.

### Owned by `sync_mappings`

- provider (`GOOGLE`)
- local type (`EVENT`, `TASK`)
- local id
- external id
- external etag
- calendar id or task list id in metadata
- active/detached/conflict/tombstone status
- last successful sync metadata

### Denormalized compatibility fields

`events.external_source_id`, `events.external_etag`, `tasks.external_source_id`, and `tasks.external_etag` may remain during migration as denormalized read/cache fields.

Rules:

- Provider write success updates `sync_mappings` and denormalized fields in one transaction.
- There must be at most one active mapping for a provider external object.
- There must be at most one active provider mapping for a local object.
- Imported/forked rows must not produce two active local rows for the same provider object.

## OAuth capability model

`calendar_connections` must persist enough provider capability data to distinguish read-only, write-enabled, degraded, and reconnect-required states.

Recommended fields:

- `granted_scopes` as text/json
- `calendar_write_enabled`
- `tasks_write_enabled`
- `capability_checked_at`
- optional `capability_error`

Rules:

- Old read-only tokens become `RECONNECT_REQUIRED` or an equivalent write-disabled status.
- `externalWriteEnabled` must be derived from stored capabilities and current token status, never hard-coded.
- UI labels must distinguish:
  - not connected
  - read-only token / reconnect required
  - write enabled
  - degraded
  - provider write failed

## Provider-write persistence ownership

Provider write intent is durable in a new write outbox table. `sync_mappings` remains identity/status owner; Events/Tasks remain business-state owner.

### Write outbox owns

- `local_type`
- `local_id`
- `mapping_id`
- operation: `CREATE`, `UPDATE`, `DELETE`
- payload snapshot
- state:
  - `DIRTY_PENDING_WRITE`
  - `WRITE_IN_FLIGHT`
  - `SYNCED`
  - `WRITE_FAILED_RETRYABLE`
  - `WRITE_FAILED_NEEDS_RECONNECT`
  - `CONFLICT_PENDING`
- attempt count
- last error
- next retry time
- created/applied timestamps

### Transaction boundaries

1. User/manual/focus/AI-approved mutation commits:
   - canonical Event/Task change
   - SyncMapping lookup/update
   - outbox row in `DIRTY_PENDING_WRITE`

2. Provider calls occur after commit through manual sync or an outbox worker.

3. Provider success commits:
   - outbox `SYNCED`
   - SyncMapping external id/etag
   - denormalized external fields

4. Provider failure commits:
   - `WRITE_FAILED_RETRYABLE` for transient failures
   - `WRITE_FAILED_NEEDS_RECONNECT` for auth/scope failures
   - user-visible sync status and error details

5. Remote/local divergence commits:
   - `CONFLICT_PENDING`
   - `SyncConflict` row
   - no silent overwrite

## Fork/detach policy

Current imported item edits fork the row and copy external ids before detaching the original. That behavior is unsafe for provider write-back because it can create ambiguous local ownership of one Google object.

New default:

- Editing an imported provider-backed item keeps the mapping on the edited canonical row.
- The row becomes dirty and gets a provider write outbox entry.
- Fork/detach is allowed only when the user explicitly chooses “keep a separate local copy”.
- Remote delete/update while a local item is dirty creates a conflict, not silent detach or overwrite.

## Consequences

- Some existing ScheduleBlock-based AI commands must be migrated to Event/Task commands.
- Existing inbound sync must be refactored to populate SyncMapping as the canonical mapping path.
- OAuth login/session/sync status contracts must grow capability fields.
- Provider write-back must be tested through mockable adapters before live Google checks.

## Required tests before Phase 1 completion

- Canonical Event/Task mutation tests for manual, focus, and AI-approved changes.
- SyncMapping uniqueness/idempotency tests.
- OAuth read-only-token and write-capability status tests.
- Provider write outbox state-transition tests.
- Fork/detach conflict tests for imported Event/Task edits.
- Dashboard/sync UI degraded/reconnect/conflict contract tests.
