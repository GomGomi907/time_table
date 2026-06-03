ALTER TABLE provider_write_outbox ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
