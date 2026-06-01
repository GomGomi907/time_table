-- Scope provider external IDs and local mappings by user.
-- Google object IDs can collide across users/shared resources; provider mappings must never be global.

create unique index if not exists idx_sync_mappings_user_provider_external_id
    on sync_mappings (user_id, provider, external_id);

create unique index if not exists idx_sync_mappings_user_local
    on sync_mappings (user_id, local_type, local_id, provider);

drop index if exists idx_sync_mappings_provider_external_id;
drop index if exists idx_sync_mappings_local;
