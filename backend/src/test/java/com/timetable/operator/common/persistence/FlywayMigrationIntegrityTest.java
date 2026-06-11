package com.timetable.operator.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlywayMigrationIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    void latestMigrationPreservesRowsAndEnforcesExternalSourceUniqueness() throws Exception {
        String jdbcUrl = "jdbc:h2:file:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
                .formatted(tempDir.resolve("migration-integrity").toAbsolutePath().toString().replace('\\', '/'));

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();

        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        String duplicateTaskExternalId = "google_tasks:@default:duplicate-task";
        String duplicateEventExternalId = "google_calendar:duplicate-event";

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            insertUser(connection, userId);
            insertUser(connection, otherUserId);
            insertTask(connection, userId, UUID.randomUUID(), duplicateTaskExternalId, Instant.parse("2026-01-01T00:00:00Z"));
            insertTask(connection, userId, UUID.randomUUID(), duplicateTaskExternalId, Instant.parse("2026-01-01T00:01:00Z"));
            insertTask(connection, userId, UUID.randomUUID(), duplicateTaskExternalId, Instant.parse("2026-01-01T00:02:00Z"));
            insertTask(connection, otherUserId, UUID.randomUUID(), duplicateTaskExternalId, Instant.parse("2026-01-01T00:00:00Z"));
            insertTask(connection, userId, UUID.randomUUID(), null, Instant.parse("2026-01-01T00:03:00Z"));
            insertTask(connection, userId, UUID.randomUUID(), null, Instant.parse("2026-01-01T00:04:00Z"));
            insertEvent(connection, userId, UUID.randomUUID(), duplicateEventExternalId, Instant.parse("2026-01-01T00:00:00Z"));
            insertEvent(connection, userId, UUID.randomUUID(), duplicateEventExternalId, Instant.parse("2026-01-01T00:01:00Z"));
            insertEvent(connection, userId, UUID.randomUUID(), duplicateEventExternalId, Instant.parse("2026-01-01T00:02:00Z"));
            insertEvent(connection, otherUserId, UUID.randomUUID(), duplicateEventExternalId, Instant.parse("2026-01-01T00:00:00Z"));
            insertEvent(connection, userId, UUID.randomUUID(), null, Instant.parse("2026-01-01T00:03:00Z"));
            insertEvent(connection, userId, UUID.randomUUID(), null, Instant.parse("2026-01-01T00:04:00Z"));
        }

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            assertEquals(0, duplicateGroupCount(connection, "tasks"));
            assertEquals(0, duplicateGroupCount(connection, "events"));
            assertEquals(6, rowCount(connection, "tasks"));
            assertEquals(6, rowCount(connection, "events"));
            assertEquals(2, externalIdCount(connection, "tasks", duplicateTaskExternalId));
            assertEquals(2, externalIdCount(connection, "events", duplicateEventExternalId));
            assertEquals(2, nullExternalIdCount(connection, "tasks"));
            assertEquals(2, nullExternalIdCount(connection, "events"));

            assertThrows(SQLException.class, () -> insertTask(
                    connection,
                    userId,
                    UUID.randomUUID(),
                    duplicateTaskExternalId,
                    Instant.parse("2026-01-01T00:05:00Z")
            ));
            assertThrows(SQLException.class, () -> insertEvent(
                    connection,
                    userId,
                    UUID.randomUUID(),
                    duplicateEventExternalId,
                    Instant.parse("2026-01-01T00:05:00Z")
            ));

            insertTask(connection, otherUserId, UUID.randomUUID(), null, Instant.parse("2026-01-01T00:05:00Z"));
            insertEvent(connection, otherUserId, UUID.randomUUID(), null, Instant.parse("2026-01-01T00:05:00Z"));
        }
    }

    @Test
    void latestMigrationScopesSyncMappingsByUser() throws Exception {
        String jdbcUrl = "jdbc:h2:file:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
                .formatted(tempDir.resolve("sync-mapping-scope").toAbsolutePath().toString().replace('\\', '/'));

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID localId = UUID.randomUUID();
        String provider = "google_calendar";
        String externalId = "shared-provider-object";

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            insertUser(connection, userId);
            insertUser(connection, otherUserId);

            insertSyncMapping(connection, userId, localId, provider, externalId);
            insertSyncMapping(connection, otherUserId, localId, provider, externalId);

            assertEquals(2, syncMappingExternalIdCount(connection, provider, externalId));
            assertThrows(SQLException.class, () -> insertSyncMapping(connection, userId, UUID.randomUUID(), provider, externalId));
            assertThrows(SQLException.class, () -> insertSyncMapping(connection, userId, localId, provider, "another-provider-object"));
        }
    }

    @Test
    void latestMigrationCreatesGoogleCalendarNotificationChannelRegistry() throws Exception {
        String jdbcUrl = "jdbc:h2:file:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
                .formatted(tempDir.resolve("notification-channel-registry").toAbsolutePath().toString().replace('\\', '/'));

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            insertUser(connection, userId);
            insertCalendarConnection(connection, userId, connectionId);
            insertGoogleCalendarNotificationChannel(
                    connection,
                    userId,
                    connectionId,
                    "channel-1",
                    "resource-1",
                    "hash-1"
            );

            assertEquals(1, rowCount(connection, "google_calendar_notification_channels"));
            assertThrows(SQLException.class, () -> insertGoogleCalendarNotificationChannel(
                    connection,
                    userId,
                    connectionId,
                    "channel-1",
                    "resource-2",
                    "hash-2"
            ));
        }
    }

    private void insertUser(Connection connection, UUID userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into users (id, created_at, updated_at, email, display_name, provider)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, userId);
            statement.setTimestamp(2, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            statement.setTimestamp(3, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            statement.setString(4, userId + "@example.com");
            statement.setString(5, "Migration Test User");
            statement.setString(6, "google");
            statement.executeUpdate();
        }
    }

    private void insertTask(Connection connection, UUID userId, UUID taskId, String externalSourceId, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into tasks (
                    id, created_at, updated_at, user_id, title, estimated_minutes, actual_minutes,
                    priority, status, source_type, sync_state, external_source_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, taskId);
            statement.setTimestamp(2, Timestamp.from(createdAt));
            statement.setTimestamp(3, Timestamp.from(createdAt));
            statement.setObject(4, userId);
            statement.setString(5, "Imported task");
            statement.setInt(6, 30);
            statement.setInt(7, 0);
            statement.setShort(8, (short) 3);
            statement.setString(9, "TODO");
            statement.setString(10, "GOOGLE_TASKS");
            statement.setString(11, "IMPORTED");
            statement.setString(12, externalSourceId);
            statement.executeUpdate();
        }
    }

    private void insertEvent(Connection connection, UUID userId, UUID eventId, String externalSourceId, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into events (
                    id, created_at, updated_at, user_id, title, start_at, end_at, priority,
                    status, category, source_type, sync_state, external_source_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, eventId);
            statement.setTimestamp(2, Timestamp.from(createdAt));
            statement.setTimestamp(3, Timestamp.from(createdAt));
            statement.setObject(4, userId);
            statement.setString(5, "Imported event");
            statement.setTimestamp(6, Timestamp.from(Instant.parse("2026-01-01T03:00:00Z")));
            statement.setTimestamp(7, Timestamp.from(Instant.parse("2026-01-01T04:00:00Z")));
            statement.setShort(8, (short) 3);
            statement.setString(9, "PLANNED");
            statement.setString(10, "WORK");
            statement.setString(11, "GOOGLE_CALENDAR");
            statement.setString(12, "IMPORTED");
            statement.setString(13, externalSourceId);
            statement.executeUpdate();
        }
    }

    private int duplicateGroupCount(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                from (
                    select user_id, external_source_id
                    from %s
                    where external_source_id is not null
                    group by user_id, external_source_id
                    having count(*) > 1
                ) duplicate_groups
                """.formatted(tableName))) {
            var resultSet = statement.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int rowCount(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select count(*) from " + tableName)) {
            var resultSet = statement.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int externalIdCount(Connection connection, String tableName, String externalSourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from " + tableName + " where external_source_id = ?")) {
            statement.setString(1, externalSourceId);
            var resultSet = statement.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int nullExternalIdCount(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from " + tableName + " where external_source_id is null")) {
            var resultSet = statement.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void insertCalendarConnection(Connection connection, UUID userId, UUID connectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into calendar_connections (
                    id, created_at, updated_at, user_id, provider, status, google_subject, email
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, connectionId);
            statement.setTimestamp(2, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            statement.setTimestamp(3, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            statement.setObject(4, userId);
            statement.setString(5, "google");
            statement.setString(6, "CONNECTED");
            statement.setString(7, "google-subject");
            statement.setString(8, userId + "@example.com");
            statement.executeUpdate();
        }
    }

    private void insertGoogleCalendarNotificationChannel(
            Connection connection,
            UUID userId,
            UUID calendarConnectionId,
            String channelId,
            String resourceId,
            String tokenHash
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into google_calendar_notification_channels (
                    id, created_at, updated_at, user_id, calendar_connection_id, calendar_id,
                    channel_id, resource_id, resource_uri, channel_token_hash, status,
                    expiration_at, last_message_number, last_notification_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setTimestamp(2, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            statement.setTimestamp(3, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
            statement.setObject(4, userId);
            statement.setObject(5, calendarConnectionId);
            statement.setString(6, "primary");
            statement.setString(7, channelId);
            statement.setString(8, resourceId);
            statement.setString(9, "https://www.googleapis.com/calendar/v3/calendars/primary/events");
            statement.setString(10, tokenHash);
            statement.setString(11, "ACTIVE");
            statement.setTimestamp(12, Timestamp.from(Instant.parse("2026-01-08T00:00:00Z")));
            statement.setLong(13, 1L);
            statement.setTimestamp(14, Timestamp.from(Instant.parse("2026-01-01T00:01:00Z")));
            statement.executeUpdate();
        }
    }

    private void insertSyncMapping(Connection connection, UUID userId, UUID localId, String provider, String externalId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into sync_mappings (
                    id, created_at, updated_at, user_id, local_type, local_id, provider,
                    external_id, sync_status, tombstone_state
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            statement.setObject(1, UUID.randomUUID());
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, userId);
            statement.setString(5, "EVENT");
            statement.setObject(6, localId);
            statement.setString(7, provider);
            statement.setString(8, externalId);
            statement.setString(9, "SYNCED");
            statement.setString(10, "ACTIVE");
            statement.executeUpdate();
        }
    }

    private int syncMappingExternalIdCount(Connection connection, String provider, String externalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from sync_mappings where provider = ? and external_id = ?")) {
            statement.setString(1, provider);
            statement.setString(2, externalId);
            var resultSet = statement.executeQuery();
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
